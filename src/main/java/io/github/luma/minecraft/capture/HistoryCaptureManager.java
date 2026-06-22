package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSectionPoint;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.minecraft.debug.HistoryDebugLog;
import io.github.luma.minecraft.world.PersistentBlockStatePolicy;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.VariantRepository;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Captures tracked world edits and fans them out into project-scoped runtime models.
 *
 * <p>This manager coordinates project matching, capture policy, stabilization,
 * and baseline chunk capture. Durable working-draft ownership lives in
 * {@link WorkingDraftSessionManager}; volatile live undo/redo action ownership
 * lives in {@link LiveUndoRedoActionRecorder}.
 */
public final class HistoryCaptureManager {

    private static final Duration ACTIVE_DRAFT_FLUSH_INTERVAL = Duration.ofSeconds(3);
    private static final int IDLE_FLUSH_TICK_INTERVAL = 5;
    private static final CaptureEligibilityService ELIGIBILITY = new CaptureEligibilityService();
    private static final EntityMutationCapturePolicy ENTITY_CAPTURE_POLICY = new EntityMutationCapturePolicy();
    private static final HistoryCaptureManager INSTANCE = new HistoryCaptureManager();

    private final HistoryDebugLog historyDebugLog = new HistoryDebugLog();
    private final CaptureDiagnosticsLogger diagnosticsLogger = new CaptureDiagnosticsLogger();
    private final BlockMutationCaptureGate blockMutationGate =
            new BlockMutationCaptureGate(ELIGIBILITY, this.diagnosticsLogger);
    private final CapturePersistenceCoordinator persistenceCoordinator = new CapturePersistenceCoordinator();
    private final WorkingDraftSessionManager workingDrafts = new WorkingDraftSessionManager(this.persistenceCoordinator);
    private final LiveUndoRedoActionRecorder liveUndoRedoActionRecorder =
            new LiveUndoRedoActionRecorder(this.historyDebugLog);
    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final TrackedProjectCatalog trackedProjectCatalog = new TrackedProjectCatalog(
            this.projectService,
            this.projectRepository,
            this.variantRepository
    );
    private final BaselineChunkRepository baselineChunkRepository = new BaselineChunkRepository();
    private final SessionStabilizationService stabilizationService = new SessionStabilizationService();
    private final CaptureBaselineCoordinator baselineCoordinator =
            new CaptureBaselineCoordinator(this.stabilizationService, new PersistentBlockStatePolicy());
    private final SessionDraftBlockChangeRecorder draftBlockChangeRecorder = new SessionDraftBlockChangeRecorder();
    private final ChunkSnapshotCaptureService chunkSnapshotCaptureService = new ChunkSnapshotCaptureService();
    private final EntitySnapshotService entitySnapshotService = new EntitySnapshotService();
    private final WorkingDraftLiveStateReconciler liveStateReconciler = new WorkingDraftLiveStateReconciler();
    private final ServerThreadExecutor serverThreadExecutor = new ServerThreadExecutor();
    private final ActiveSessionRegionPolicy activeSessionRegionPolicy = new ActiveSessionRegionPolicy();
    private long idleFlushTicker;

    private HistoryCaptureManager() {
    }

    public record BlockChangeInput(
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            CompoundTag oldBlockEntity,
            CompoundTag newBlockEntity
    ) {

        public BlockChangeInput {
            pos = pos == null ? null : pos.immutable();
            oldBlockEntity = oldBlockEntity == null ? null : oldBlockEntity.copy();
            newBlockEntity = newBlockEntity == null ? null : newBlockEntity.copy();
        }
    }

    public static HistoryCaptureManager getInstance() {
        return INSTANCE;
    }

    /**
     * Captures the active-session baseline before a root mutation can trigger
     * synchronous neighbor fallout in the same chunk.
     */
    public void capturePreMutationBaseline(
            ServerLevel level,
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity
    ) {
        io.github.luma.domain.model.WorldMutationSource source = WorldMutationContext.currentSource();
        boolean explicitRootSource = ELIGIBILITY.isExplicitRootSource(source);
        if (level == null
                || pos == null
                || !shouldCaptureMutation(source)
                || !this.canUseMutationSource(level.getServer(), source)) {
            return;
        }

        try {
            Instant now = Instant.now();
            List<TrackedProject> matchingProjects = this.matchingProjects(level, pos);
            if (matchingProjects.isEmpty()) {
                if (!explicitRootSource || !allowsAutomaticProjectCreation(source)) {
                    return;
                }
                this.projectService.ensureWorldProject(level, defaultActor(source));
                this.invalidateProjectCache(level.getServer());
                matchingProjects = this.matchingProjects(level, pos);
            }

            for (TrackedProject trackedProject : matchingProjects) {
                String projectId = trackedProject.project().id().toString();
                CaptureSessionState existingSession = this.workingDrafts.session(projectId);
                ChunkPoint chunk = ChunkPoint.from(pos);
                boolean activeSessionRegion = this.activeSessionRegionPolicy.contains(level, existingSession, chunk);
                CaptureSessionState.DeferredActionContext deferredActionContext =
                        this.deferredActionContext(existingSession, chunk, source);
                if (!explicitRootSource
                        && (existingSession == null
                        || !ELIGIBILITY.canCaptureDeferredPreMutationBaseline(
                                trackedProject.project(),
                                source,
                                activeSessionRegion,
                                actionId(deferredActionContext)
                        ))) {
                    continue;
                }
                if (!this.canCaptureIntoSession(trackedProject, level, source, pos)) {
                    continue;
                }

                this.getOrCreateWorkingDraft(trackedProject, source, now);
                CaptureSessionState session = this.workingDrafts.session(projectId);
                if (session == null || !this.ensureTrackedChunk(
                        trackedProject,
                        level,
                        pos,
                        oldState,
                        oldBlockEntity,
                        source,
                        activeSessionRegion,
                        now
                )) {
                    continue;
                }

                this.baselineCoordinator.captureSessionChunkBaseline(
                        trackedProject,
                        level,
                        session,
                        chunk,
                        pos,
                        oldState,
                        oldBlockEntity
                );
                if (!explicitRootSource) {
                    this.baselineCoordinator.recordBaselineCorrection(session, pos, oldState, oldBlockEntity);
                }
                if (explicitRootSource) {
                    session.addRootChunk(chunk);
                }
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to capture pre-mutation baseline at {} in {}", pos, level.dimension().identifier(), exception);
        }
    }

    /**
     * Records one block mutation for any tracked source except internal restore
     * application.
     *
     * <p>The manager resolves matching projects, creates a world workspace on
     * demand if none exist, captures baseline chunk state when required, and
     * merges the change into the active {@link TrackedChangeBuffer}.
     */
    public void recordBlockChange(
            ServerLevel level,
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            CompoundTag oldBlockEntity,
            CompoundTag newBlockEntity
    ) {
        io.github.luma.domain.model.WorldMutationSource source = WorldMutationContext.currentSource();
        if (!shouldCaptureMutation(source) || !this.canUseMutationSource(level.getServer(), source)) {
            return;
        }

        try {
            Instant now = Instant.now();
            WorldMutationCapturePolicy.CaptureResult captureResult = null;
            List<TrackedProject> matchingProjects = this.matchingProjects(level, pos);
            if (matchingProjects.isEmpty()) {
                if (!allowsAutomaticProjectCreation(source)) {
                    LumaDebugLog.log(
                            "capture",
                            "Skipped {} mutation at {} in {} because no tracked workspace exists and the source cannot bootstrap one",
                            source,
                            pos,
                            level.dimension().identifier()
                    );
                    return;
                }
                captureResult = this.blockMutationGate.evaluate(source, pos, oldState, newState, oldBlockEntity, newBlockEntity);
                if (captureResult.decision() == WorldMutationCapturePolicy.CaptureDecision.REJECTED) {
                    this.blockMutationGate.logRejected(level, source, pos, oldState, newState);
                    return;
                }
                if (captureResult.decision() != WorldMutationCapturePolicy.CaptureDecision.CAPTURED) {
                    LumaDebugLog.log(
                            "capture",
                            "Skipped {} mutation at {} in {} because it cannot bootstrap a tracked workspace",
                            source,
                            pos,
                            level.dimension().identifier()
                    );
                    return;
                }
                this.projectService.ensureWorldProject(level, defaultActor(source));
                this.invalidateProjectCache(level.getServer());
                matchingProjects = this.matchingProjects(level, pos);
                LumaMod.LOGGER.info("Created world workspace automatically for dimension {}", level.dimension().identifier());
            }

            if (matchingProjects.isEmpty()) {
                LumaDebugLog.log(
                        "capture",
                        "Skipped mutation at {} in {} because no tracked workspace matched source={}",
                        pos,
                        level.dimension().identifier(),
                        source
                );
            }

            for (TrackedProject trackedProject : matchingProjects) {
                String projectId = trackedProject.project().id().toString();
                CaptureSessionState existingSession = this.workingDrafts.session(projectId);
                ChunkPoint chunk = ChunkPoint.from(pos);
                boolean activeSessionRegion = this.activeSessionRegionPolicy.contains(level, existingSession, chunk);
                CaptureSessionState.DeferredActionContext deferredActionContext =
                        this.deferredActionContext(existingSession, chunk, source);
                boolean usesDeferredStabilization = ELIGIBILITY.usesDeferredStabilization(
                        trackedProject.project(),
                        source
                );
                if (!this.blockMutationGate.canInspectPayload(
                        trackedProject,
                        source,
                        pos,
                        existingSession != null,
                        activeSessionRegion,
                        deferredActionContext
                )) {
                    continue;
                }
                if (captureResult == null) {
                    captureResult = this.blockMutationGate.evaluate(
                            source,
                            pos,
                            oldState,
                            newState,
                            oldBlockEntity,
                            newBlockEntity
                    );
                    if (captureResult.decision() == WorldMutationCapturePolicy.CaptureDecision.REJECTED) {
                        this.diagnosticsLogger.logSkippedCapture(
                                trackedProject,
                                source,
                                pos,
                                "unsupported-unchanged-or-transient",
                                "mutation is unsupported, unchanged, or transient"
                        );
                        return;
                    }
                }
                if (usesDeferredStabilization
                        && !ELIGIBILITY.canUseDeferredStabilization(
                                trackedProject.project(),
                                source,
                                activeSessionRegion,
                                actionId(deferredActionContext)
                        )) {
                    this.diagnosticsLogger.logSkippedCapture(
                            trackedProject,
                            source,
                            pos,
                            "missing-causal-action",
                            "no causal action is active"
                    );
                    continue;
                }
                WorldMutationCapturePolicy.CapturedMutation mutation = captureResult.mutation();
                StoredBlockChange capturedChange = mutation == null ? null : mutation.change();
                if (!this.canCaptureIntoSession(trackedProject, level, source, pos)) {
                    continue;
                }
                if (!this.ensureTrackedChunk(
                        trackedProject,
                        level,
                        pos,
                        mutation == null ? oldState : mutation.oldState(),
                        mutation == null ? oldBlockEntity : mutation.oldBlockEntity(),
                        source,
                        activeSessionRegion,
                        now
                )) {
                    continue;
                }
                if (captureResult.decision() == WorldMutationCapturePolicy.CaptureDecision.DEFER_TO_STABILIZATION) {
                    this.recordDeferredBlockMutation(
                            trackedProject,
                            level,
                            source,
                            pos,
                            chunk,
                            oldState,
                            oldBlockEntity,
                            now,
                            deferredActionContext
                    );
                    continue;
                }
                TrackedChangeBuffer buffer = this.getOrCreateWorkingDraft(trackedProject, source, now);
                CaptureSessionState session = this.workingDrafts.session(projectId);
                if (session == null) {
                    continue;
                }
                if (mutation != null && !session.isRootChunk(chunk)) {
                    this.baselineCoordinator.recordBaselineCorrection(
                            session,
                            pos,
                            mutation.oldState(),
                            mutation.oldBlockEntity()
                    );
                }
                if (ELIGIBILITY.isExplicitRootSource(source)) {
                    this.baselineCoordinator.captureSessionChunkBaseline(
                            trackedProject,
                            level,
                            session,
                            chunk,
                            pos,
                            mutation.oldState(),
                            mutation.oldBlockEntity()
                    );
                    session.addRootChunk(chunk);
                } else if (usesDeferredStabilization) {
                    if (!this.activeSessionRegionPolicy.contains(level, session, chunk)) {
                        LumaDebugLog.log(
                                trackedProject.project(),
                                "capture",
                                "Skipped deferred {} mutation at {} for project {} because chunk {}:{} is outside the active session region",
                                source,
                                pos,
                                trackedProject.project().name(),
                                chunk.x(),
                                chunk.z()
                        );
                        continue;
                    }
                    this.baselineCoordinator.captureSessionChunkBaseline(
                            trackedProject,
                            level,
                            session,
                            chunk,
                            pos,
                            mutation.oldState(),
                            mutation.oldBlockEntity()
                    );
                    session.markDirtySection(
                            new ChunkSectionPoint(chunk, Math.floorDiv(pos.getY(), 16)),
                            this.deferredActionContext(session, chunk, source),
                            level.getGameTime()
                    );
                    this.workingDrafts.markDirty(projectId);
                    LumaDebugLog.log(
                        trackedProject.project(),
                        "capture",
                        "Marked chunk {}:{} dirty for deferred {} stabilization in project {}",
                            chunk.x(),
                            chunk.z(),
                            source,
                            trackedProject.project().name()
                    );
                    continue;
                }
                SessionDraftBlockChangeRecorder.Result draftRecord =
                        this.draftBlockChangeRecorder.record(session, buffer, capturedChange, now);
                this.liveUndoRedoActionRecorder.recordBlockAction(trackedProject, level, capturedChange, now);
                this.historyDebugLog.logCapturedBlock(
                        trackedProject.project(),
                        "direct",
                        source,
                        pos,
                        mutation.oldState(),
                        mutation.newState(),
                        draftRecord.pendingBefore(),
                        draftRecord.pendingAfter()
                );
                CaptureSessionDiagnostics diagnostics = this.workingDrafts.diagnosticsForSession(projectId);
                diagnostics.record(
                        source,
                        pos,
                        mutation.oldState(),
                        mutation.newState(),
                        mutation.oldBlockEntity() != null,
                        mutation.newBlockEntity() != null
                );
                this.diagnosticsLogger.logAcceptedCaptureTrace(
                        trackedProject.project(),
                        buffer,
                        diagnostics,
                        draftRecord.pendingBefore(),
                        draftRecord.pendingAfter()
                );
                LumaDebugLog.log(
                        trackedProject.project(),
                        "capture",
                        "Tracked buffer {} for project {} now has {} pending changes on variant {} from base {}",
                        buffer.id(),
                        trackedProject.project().name(),
                        buffer.size(),
                        buffer.variantId(),
                        buffer.baseVersionId()
                );
                this.diagnosticsLogger.logBufferProgress(trackedProject.project(), buffer, diagnostics);
                if (buffer.isEmpty()) {
                    this.workingDrafts.discardIfEmpty(trackedProject, "after block capture");
                } else {
                    this.workingDrafts.markDirty(projectId);
                }
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to capture block change at {} in {}", pos, level.dimension().identifier(), exception);
        }
    }

    public void recordBlockChanges(
            ServerLevel level,
            List<BlockChangeInput> changes
    ) {
        io.github.luma.domain.model.WorldMutationSource source = WorldMutationContext.currentSource();
        if (level == null
                || changes == null
                || changes.isEmpty()
                || !shouldCaptureMutation(source)
                || !this.canUseMutationSource(level.getServer(), source)) {
            return;
        }

        try {
            Instant now = Instant.now();
            boolean autoWorkspaceCreated = false;
            Map<String, TrackedProject> liveUndoProjects = new LinkedHashMap<>();
            Map<String, List<StoredBlockChange>> liveUndoChanges = new LinkedHashMap<>();
            for (BlockChangeInput input : changes) {
                if (input == null) {
                    continue;
                }
                try {
                    autoWorkspaceCreated = this.recordBulkBlockChange(
                            level,
                            input,
                            source,
                            now,
                            autoWorkspaceCreated,
                            liveUndoProjects,
                            liveUndoChanges
                    );
                } catch (Exception exception) {
                    LumaMod.LOGGER.warn("Failed to capture bulk block change at {} in {}", input.pos(), level.dimension().identifier(), exception);
                }
            }

            for (Map.Entry<String, List<StoredBlockChange>> entry : liveUndoChanges.entrySet()) {
                TrackedProject trackedProject = liveUndoProjects.get(entry.getKey());
                if (trackedProject != null) {
                    this.liveUndoRedoActionRecorder.recordBlockAction(trackedProject, level, entry.getValue(), now);
                }
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to capture {} block changes in {}", changes.size(), level.dimension().identifier(), exception);
        }
    }

    private boolean recordBulkBlockChange(
            ServerLevel level,
            BlockChangeInput input,
            io.github.luma.domain.model.WorldMutationSource source,
            Instant now,
            boolean autoWorkspaceCreated,
            Map<String, TrackedProject> liveUndoProjects,
            Map<String, List<StoredBlockChange>> liveUndoChanges
    ) throws IOException {
        WorldMutationCapturePolicy.CaptureResult captureResult = ELIGIBILITY.evaluateBlockMutation(
                source,
                input.pos(),
                input.oldState(),
                input.newState(),
                input.oldBlockEntity(),
                input.newBlockEntity()
        );
        if (captureResult.decision() == WorldMutationCapturePolicy.CaptureDecision.REJECTED) {
            return autoWorkspaceCreated;
        }

        List<TrackedProject> matchingProjects = this.matchingProjects(level, input.pos());
        if (matchingProjects.isEmpty()
                && !autoWorkspaceCreated
                && captureResult.decision() == WorldMutationCapturePolicy.CaptureDecision.CAPTURED
                && allowsAutomaticProjectCreation(source)) {
            this.projectService.ensureWorldProject(level, defaultActor(source));
            this.invalidateProjectCache(level.getServer());
            matchingProjects = this.matchingProjects(level, input.pos());
            autoWorkspaceCreated = true;
            LumaMod.LOGGER.info("Created world workspace automatically for dimension {}", level.dimension().identifier());
        }
        if (matchingProjects.isEmpty()) {
            return autoWorkspaceCreated;
        }

        for (TrackedProject trackedProject : matchingProjects) {
            this.recordBulkBlockChangeForProject(
                    level,
                    trackedProject,
                    source,
                    now,
                    input,
                    captureResult,
                    liveUndoProjects,
                    liveUndoChanges
            );
        }
        return autoWorkspaceCreated;
    }

    private void recordBulkBlockChangeForProject(
            ServerLevel level,
            TrackedProject trackedProject,
            io.github.luma.domain.model.WorldMutationSource source,
            Instant now,
            BlockChangeInput input,
            WorldMutationCapturePolicy.CaptureResult captureResult,
            Map<String, TrackedProject> liveUndoProjects,
            Map<String, List<StoredBlockChange>> liveUndoChanges
    ) throws IOException {
        String projectId = trackedProject.project().id().toString();
        CaptureSessionState existingSession = this.workingDrafts.session(projectId);
        ChunkPoint chunk = ChunkPoint.from(input.pos());
        boolean activeSessionRegion = this.activeSessionRegionPolicy.contains(level, existingSession, chunk);
        CaptureSessionState.DeferredActionContext deferredActionContext =
                this.deferredActionContext(existingSession, chunk, source);
        boolean usesDeferredStabilization = ELIGIBILITY.usesDeferredStabilization(trackedProject.project(), source);
        if (usesDeferredStabilization
                && !ELIGIBILITY.canUseDeferredStabilization(
                        trackedProject.project(),
                        source,
                        activeSessionRegion,
                        actionId(deferredActionContext)
                )) {
            this.diagnosticsLogger.logSkippedCapture(
                    trackedProject,
                    source,
                    input.pos(),
                    "missing-causal-action",
                    "no causal action is active"
            );
            return;
        }
        if (!this.canCaptureIntoSession(trackedProject, level, source, input.pos())) {
            return;
        }

        WorldMutationCapturePolicy.CapturedMutation mutation = captureResult.mutation();
        if (!this.ensureTrackedChunk(
                trackedProject,
                level,
                input.pos(),
                mutation == null ? input.oldState() : mutation.oldState(),
                mutation == null ? input.oldBlockEntity() : mutation.oldBlockEntity(),
                source,
                activeSessionRegion,
                now
        )) {
            return;
        }
        if (captureResult.decision() == WorldMutationCapturePolicy.CaptureDecision.DEFER_TO_STABILIZATION) {
            this.recordDeferredBlockMutation(
                    trackedProject,
                    level,
                    source,
                    input.pos(),
                    chunk,
                    input.oldState(),
                    input.oldBlockEntity(),
                    now,
                    deferredActionContext
            );
            return;
        }

        TrackedChangeBuffer buffer = this.getOrCreateWorkingDraft(trackedProject, source, now);
        CaptureSessionState session = this.workingDrafts.session(projectId);
        if (session == null || mutation == null) {
            this.diagnosticsLogger.logSkippedCapture(
                    trackedProject,
                    source,
                    input.pos(),
                    "missing-session-or-mutation",
                    "capture session or captured mutation was unavailable"
            );
            return;
        }
        if (!session.isRootChunk(chunk)) {
            this.baselineCoordinator.recordBaselineCorrection(
                    session,
                    input.pos(),
                    mutation.oldState(),
                    mutation.oldBlockEntity()
            );
        }
        if (ELIGIBILITY.isExplicitRootSource(source)) {
            this.baselineCoordinator.captureSessionChunkBaseline(
                    trackedProject,
                    level,
                    session,
                    chunk,
                    input.pos(),
                    mutation.oldState(),
                    mutation.oldBlockEntity()
            );
            session.addRootChunk(chunk);
        } else if (usesDeferredStabilization) {
            if (!this.activeSessionRegionPolicy.contains(level, session, chunk)) {
                this.diagnosticsLogger.logSkippedCapture(
                        trackedProject,
                        source,
                        input.pos(),
                        "outside-active-session-region",
                        "chunk " + chunk.x() + ":" + chunk.z() + " is outside the active session region"
                );
                return;
            }
            this.baselineCoordinator.captureSessionChunkBaseline(
                    trackedProject,
                    level,
                    session,
                    chunk,
                    input.pos(),
                    mutation.oldState(),
                    mutation.oldBlockEntity()
            );
            session.markDirtySection(
                    new ChunkSectionPoint(chunk, Math.floorDiv(input.pos().getY(), 16)),
                    this.deferredActionContext(session, chunk, source),
                    level.getGameTime()
            );
            this.workingDrafts.markDirty(projectId);
            return;
        }

        StoredBlockChange capturedChange = mutation.change();
        SessionDraftBlockChangeRecorder.Result draftRecord =
                this.draftBlockChangeRecorder.record(session, buffer, capturedChange, now);
        liveUndoProjects.putIfAbsent(projectId, trackedProject);
        liveUndoChanges.computeIfAbsent(projectId, ignored -> new ArrayList<>()).add(capturedChange);
        CaptureSessionDiagnostics diagnostics = this.workingDrafts.diagnosticsForSession(projectId);
        diagnostics.record(
                source,
                input.pos(),
                mutation.oldState(),
                mutation.newState(),
                mutation.oldBlockEntity() != null,
                mutation.newBlockEntity() != null
        );
        this.diagnosticsLogger.logAcceptedCaptureTrace(
                trackedProject.project(),
                buffer,
                diagnostics,
                draftRecord.pendingBefore(),
                draftRecord.pendingAfter()
        );
        if (buffer.isEmpty()) {
            this.workingDrafts.discardIfEmpty(trackedProject, "after bulk block capture");
        } else {
            this.workingDrafts.markDirty(projectId);
        }
        this.diagnosticsLogger.logBufferProgress(trackedProject.project(), buffer, diagnostics);
    }

    public void recordEntityChange(
            ServerLevel level,
            EntityPayload oldPayload,
            EntityPayload newPayload
    ) {
        this.recordEntityChange(level, oldPayload, newPayload, null);
    }

    public void recordDelayedEntityChange(
            ServerLevel level,
            EntityPayload oldPayload,
            EntityPayload newPayload,
            Instant actionStartedAt
    ) {
        this.recordEntityChange(level, oldPayload, newPayload, actionStartedAt);
    }

    private void recordEntityChange(
            ServerLevel level,
            EntityPayload oldPayload,
            EntityPayload newPayload,
            Instant actionStartedAt
    ) {
        io.github.luma.domain.model.WorldMutationSource source = WorldMutationContext.currentSource();
        if (!this.canUseMutationSource(level.getServer(), source)) {
            return;
        }

        try {
            Optional<StoredEntityChange> capturedMutation = ENTITY_CAPTURE_POLICY.capture(source, oldPayload, newPayload);
            if (capturedMutation.isEmpty()) {
                return;
            }
            StoredEntityChange capturedChange = capturedMutation.get();
            BlockPos pos = this.entityMutationPos(oldPayload, newPayload);
            List<BlockPos> mutationPositions = this.entityMutationPositions(oldPayload, newPayload);
            Instant now = Instant.now();
            List<TrackedProject> matchingProjects = this.matchingEntityProjects(level, mutationPositions);
            if (matchingProjects.isEmpty()) {
                if (!allowsAutomaticProjectCreation(source)) {
                    LumaDebugLog.log(
                            "capture",
                            "Skipped {} entity mutation at {} in {} because no tracked workspace exists and the source cannot bootstrap one",
                            source,
                            pos,
                            level.dimension().identifier()
                    );
                    return;
                }
                this.projectService.ensureWorldProject(level, defaultActor(source));
                this.invalidateProjectCache(level.getServer());
                matchingProjects = this.matchingEntityProjects(level, mutationPositions);
                LumaMod.LOGGER.info("Created world workspace automatically for entity mutation in {}", level.dimension().identifier());
            }

            for (TrackedProject trackedProject : matchingProjects) {
                if (!this.canCaptureIntoSession(trackedProject, level, source, pos)) {
                    continue;
                }
                String projectId = trackedProject.project().id().toString();
                CaptureSessionState existingSession = this.workingDrafts.session(projectId);
                List<ChunkPoint> mutationChunks = this.entityMutationChunks(mutationPositions);
                if (!this.ensureTrackedEntityChunks(
                        trackedProject,
                        level,
                        mutationPositions,
                        capturedChange,
                        source,
                        existingSession,
                        now
                )) {
                    continue;
                }

                TrackedChangeBuffer buffer = this.getOrCreateWorkingDraft(trackedProject, source, now);
                CaptureSessionState session = this.workingDrafts.session(projectId);
                if (session != null) {
                    for (ChunkPoint chunk : mutationChunks) {
                        session.addRootChunk(chunk);
                    }
                }

                int pendingBefore = buffer.size();
                buffer.addEntityChange(capturedChange, now);
                this.liveUndoRedoActionRecorder.recordEntityAction(trackedProject, level, capturedChange, now, actionStartedAt);
                int pendingAfter = buffer.size();
                this.workingDrafts.diagnosticsForSession(projectId).addActiveChunk(new ChunkPoint(pos.getX() >> 4, pos.getZ() >> 4));
                LumaDebugLog.log(
                        trackedProject.project(),
                        "capture",
                        "Tracked entity {} mutation {} for project {} at {} pending={} delta={}",
                        source,
                        capturedChange.entityId(),
                        trackedProject.project().name(),
                        pos,
                        pendingAfter,
                        pendingAfter - pendingBefore
                );
                this.diagnosticsLogger.logBufferProgress(
                        trackedProject.project(),
                        buffer,
                        this.workingDrafts.diagnosticsForSession(projectId)
                );
                if (buffer.isEmpty()) {
                    this.workingDrafts.discardIfEmpty(trackedProject, "after entity capture");
                } else {
                    this.workingDrafts.markDirty(projectId);
                }
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to capture entity change in {}", level.dimension().identifier(), exception);
        }
    }

    public void recordUndoOnlyEntityChange(
            ServerLevel level,
            EntityPayload oldPayload,
            EntityPayload newPayload
    ) {
        this.recordUndoOnlyEntityChange(level, oldPayload, newPayload, null);
    }

    public void recordDelayedUndoOnlyEntityChange(
            ServerLevel level,
            EntityPayload oldPayload,
            EntityPayload newPayload,
            Instant actionStartedAt
    ) {
        this.recordUndoOnlyEntityChange(level, oldPayload, newPayload, actionStartedAt);
    }

    private void recordUndoOnlyEntityChange(
            ServerLevel level,
            EntityPayload oldPayload,
            EntityPayload newPayload,
            Instant actionStartedAt
    ) {
        io.github.luma.domain.model.WorldMutationSource source = WorldMutationContext.currentSource();
        if (!this.canUseMutationSource(level.getServer(), source)) {
            return;
        }

        try {
            Optional<StoredEntityChange> capturedMutation = ENTITY_CAPTURE_POLICY.captureUndoOnly(source, oldPayload, newPayload);
            if (capturedMutation.isEmpty()) {
                return;
            }
            StoredEntityChange capturedChange = capturedMutation.get();
            BlockPos pos = this.entityMutationPos(oldPayload, newPayload);
            Instant now = Instant.now();
            for (TrackedProject trackedProject : this.matchingProjects(level, pos)) {
                this.liveUndoRedoActionRecorder.recordEntityAction(trackedProject, level, capturedChange, now, actionStartedAt);
                LumaDebugLog.log(
                        trackedProject.project(),
                        "capture",
                        "Tracked undo-only entity {} mutation {} for project {} at {}",
                        source,
                        capturedChange.entityId(),
                        trackedProject.project().name(),
                        pos
                );
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to capture undo-only entity change in {}", level.dimension().identifier(), exception);
        }
    }

    /**
     * Reconciles the working draft after an internal undo/redo world
     * operation has already applied the same state transition to the world.
     */
    public void applyLiveActionAdjustments(
            MinecraftServer server,
            String projectId,
            List<StoredBlockChange> changes,
            String actor,
            Instant now
    ) throws IOException {
        this.applyLiveActionAdjustments(server, projectId, changes, List.of(), actor, now);
    }

    public void applyLiveActionAdjustments(
            MinecraftServer server,
            String projectId,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            String actor,
            Instant now
    ) throws IOException {
        this.serverThreadExecutor.run(server, () -> this.applyLiveActionAdjustmentsOnServerThread(
                server,
                projectId,
                changes,
                entityChanges,
                actor,
                now
        ));
    }

    public void applyUndoRedoAdjustments(
            MinecraftServer server,
            String projectId,
            List<StoredBlockChange> changes,
            String actor,
            Instant now
    ) throws IOException {
        this.applyLiveActionAdjustments(server, projectId, changes, actor, now);
    }

    public void applyUndoRedoAdjustments(
            MinecraftServer server,
            String projectId,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            String actor,
            Instant now
    ) throws IOException {
        this.applyLiveActionAdjustments(server, projectId, changes, entityChanges, actor, now);
    }

    private void applyLiveActionAdjustmentsOnServerThread(
            MinecraftServer server,
            String projectId,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            String actor,
            Instant now
    ) throws IOException {
        if ((changes == null || changes.isEmpty()) && (entityChanges == null || entityChanges.isEmpty())) {
            return;
        }

        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        if (trackedProject == null) {
            return;
        }

        TrackedChangeBuffer buffer = this.getOrCreateWorkingDraft(
                trackedProject,
                io.github.luma.domain.model.WorldMutationSource.PLAYER,
                now
        );
        CaptureSessionState session = this.workingDrafts.session(projectId);
        for (StoredBlockChange change : changes == null ? List.<StoredBlockChange>of() : changes) {
            buffer.addChange(change, now);
            if (session != null) {
                session.addRootChunk(ChunkPoint.from(change.pos()));
            }
        }
        for (StoredEntityChange change : entityChanges == null ? List.<StoredEntityChange>of() : entityChanges) {
            buffer.addEntityChange(change, now);
            if (session != null) {
                session.addRootChunk(change.chunk());
            }
        }

        if (buffer.isEmpty()) {
            this.workingDrafts.discardIfEmpty(trackedProject, "after undo/redo");
        } else {
            this.workingDrafts.markDirty(projectId);
            LumaMod.LOGGER.info(
                    "Adjusted working draft for project {} after undo/redo by {} with {} changes; pending={}",
                    trackedProject.project().name(),
                    actor == null || actor.isBlank() ? "player" : actor,
                    (changes == null ? 0 : changes.size()) + (entityChanges == null ? 0 : entityChanges.size()),
                    buffer.size()
            );
        }
    }

    public void finalizeProjectSession(MinecraftServer server, String projectId) throws IOException {
        this.freezeWorkingDraftForRecovery(server, projectId);
    }

    public Optional<RecoveryDraft> snapshotDraft(MinecraftServer server, String projectId) throws IOException {
        return this.serverThreadExecutor.call(server, () -> this.snapshotDraftOnServerThread(server, projectId));
    }

    public boolean hasInterruptedDraft(MinecraftServer server, String projectId) throws IOException {
        return this.serverThreadExecutor.call(server, () -> this.hasInterruptedDraftOnServerThread(server, projectId));
    }

    public void markPersistedDraftCurrentRun(MinecraftServer server, String projectId) throws IOException {
        this.serverThreadExecutor.run(server, () -> this.workingDrafts.markPersistedDraftCurrentRun(projectId));
    }

    private Optional<RecoveryDraft> snapshotDraftOnServerThread(MinecraftServer server, String projectId) throws IOException {
        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        CaptureSessionState sessionState = this.workingDrafts.session(projectId);
        if (trackedProject != null && sessionState != null) {
            this.reconcileSession(server, trackedProject, sessionState, true);
        }
        if (trackedProject == null) {
            return Optional.empty();
        }
        return this.workingDrafts.snapshotDraft(trackedProject);
    }

    private boolean hasInterruptedDraftOnServerThread(MinecraftServer server, String projectId) throws IOException {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        return this.workingDrafts.hasInterruptedDraft(projectId, trackedProject);
    }

    /**
     * Freezes the active working draft and keeps it durably persisted as a
     * recovery draft.
     *
     * <p>This is used before operations such as restore or variant switching
     * where capture must stop and the current pending state must survive an
     * interrupted workflow.
     */
    public Optional<TrackedChangeBuffer> freezeWorkingDraft(MinecraftServer server, String projectId) throws IOException {
        return this.serverThreadExecutor.call(server, () -> this.freezeWorkingDraftOnServerThread(server, projectId));
    }

    public Optional<TrackedChangeBuffer> freezeWorkingDraftForRecovery(
            MinecraftServer server,
            String projectId
    ) throws IOException {
        return this.serverThreadExecutor.call(server, () -> this.freezeWorkingDraftForRecoveryOnServerThread(server, projectId));
    }

    private Optional<TrackedChangeBuffer> freezeWorkingDraftOnServerThread(MinecraftServer server, String projectId) throws IOException {
        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        CaptureSessionState sessionState = this.workingDrafts.session(projectId);
        if (trackedProject != null && sessionState != null) {
            this.reconcileSession(server, trackedProject, sessionState, true);
        }
        return this.workingDrafts.freezeAfterReconciliation(projectId, trackedProject);
    }

    private Optional<TrackedChangeBuffer> freezeWorkingDraftForRecoveryOnServerThread(
            MinecraftServer server,
            String projectId
    ) throws IOException {
        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        CaptureSessionState sessionState = this.workingDrafts.session(projectId);
        if (trackedProject != null && sessionState != null) {
            this.reconcileSession(server, trackedProject, sessionState, true);
        }
        return this.workingDrafts.freezeForRecoveryAfterReconciliation(projectId, trackedProject);
    }

    /**
     * Removes and returns the active working draft for save operations.
     *
     * <p>Save prefers the active in-memory draft so it can avoid reloading the
     * compacted recovery draft unless the client was restarted or the session was
     * already flushed out of memory.
     */
    public Optional<TrackedChangeBuffer> consumeWorkingDraft(MinecraftServer server, String projectId) throws IOException {
        return this.serverThreadExecutor.call(server, () -> this.consumeWorkingDraftOnServerThread(server, projectId));
    }

    private Optional<TrackedChangeBuffer> consumeWorkingDraftOnServerThread(MinecraftServer server, String projectId) throws IOException {
        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        CaptureSessionState sessionState = this.workingDrafts.session(projectId);
        if (trackedProject != null && sessionState != null) {
            this.reconcileSession(server, trackedProject, sessionState, true);
        }
        return this.workingDrafts.consumeAfterReconciliation(projectId, trackedProject);
    }

    public void discardSession(MinecraftServer server, String projectId) throws IOException {
        this.serverThreadExecutor.run(server, () -> {
            TrackedProject trackedProject = this.findTrackedProject(server, projectId);
            this.workingDrafts.discard(projectId, trackedProject);
        });
    }

    public void rebaseWorkingDraftBase(
            MinecraftServer server,
            String projectId,
            String expectedBaseVersionId,
            String newBaseVersionId
    ) throws IOException {
        this.serverThreadExecutor.run(server, () -> {
            TrackedProject trackedProject = this.findTrackedProject(server, projectId);
            this.workingDrafts.rebaseBaseVersion(
                    trackedProject,
                    expectedBaseVersionId,
                    newBaseVersionId,
                    Instant.now()
            );
        });
    }

    public void flushIdleSessions(MinecraftServer server) {
        this.idleFlushTicker += 1;
        if (this.idleFlushTicker % IDLE_FLUSH_TICK_INTERVAL != 0) {
            return;
        }
        List<Map.Entry<String, TrackedChangeBuffer>> activeEntries = this.workingDrafts.activeBufferEntries();
        if (activeEntries.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        List<String> sessionsToFinalize = new ArrayList<>();
        Map<String, Integer> idleThresholds = new HashMap<>();
        Map<String, TrackedProject> trackedProjects = new HashMap<>();

        try {
            for (TrackedProject trackedProject : this.trackedProjectCatalog.loadAll(server)) {
                String projectId = trackedProject.project().id().toString();
                trackedProjects.put(projectId, trackedProject);
                idleThresholds.put(projectId, trackedProject.project().settings().sessionIdleSeconds());
            }
        } catch (IOException exception) {
            LumaMod.LOGGER.warn("Failed to load tracked projects for idle flush", exception);
        }

        for (Map.Entry<String, TrackedChangeBuffer> entry : activeEntries) {
            String projectId = entry.getKey();
            TrackedChangeBuffer session = entry.getValue();
            int idleSeconds = idleThresholds.getOrDefault(projectId, 5);
            boolean idle = Duration.between(session.updatedAt(), now).getSeconds() >= idleSeconds;
            boolean dirty = this.workingDrafts.isDirty(projectId);
            if (idle && !dirty) {
                if (this.workingDrafts.hasPendingDraftFlush(projectId)) {
                    continue;
                }
                TrackedProject trackedProject = trackedProjects.get(projectId);
                if (trackedProject != null) {
                    LumaDebugLog.log(
                            trackedProject.project(),
                            "capture",
                            "Idle timeout reached for project {} after {}s with {} pending changes",
                            trackedProject.project().name(),
                            idleSeconds,
                            session.size()
                    );
                }
                sessionsToFinalize.add(projectId);
                continue;
            }

            if (!dirty) {
                continue;
            }

            TrackedProject trackedProject = trackedProjects.get(projectId);
            if (trackedProject == null) {
                continue;
            }

            try {
                CaptureSessionState sessionState = this.workingDrafts.session(projectId);
                if (trackedProject != null && sessionState != null) {
                    this.reconcileSession(server, trackedProject, sessionState, false);
                    if (sessionState.hasPendingReconciliation()) {
                        continue;
                    }
                }
                if (!this.workingDrafts.hasBuffer(projectId) || session.isEmpty()) {
                    continue;
                }
                this.workingDrafts.persistIdleDraft(
                        trackedProject,
                        session,
                        now,
                        ACTIVE_DRAFT_FLUSH_INTERVAL
                );
            } catch (IOException exception) {
                LumaMod.LOGGER.warn("Failed to flush active working draft for {}", projectId, exception);
            }
        }

        for (String projectId : sessionsToFinalize) {
            try {
                this.serverThreadExecutor.run(server, () -> {
                    TrackedProject trackedProject = this.findTrackedProject(server, projectId);
                    CaptureSessionState sessionState = this.workingDrafts.session(projectId);
                    if (trackedProject != null && sessionState != null) {
                        this.reconcileSession(server, trackedProject, sessionState, true);
                    }
                    this.workingDrafts.freezeIdleAfterReconciliation(projectId, trackedProject);
                });
            } catch (IOException exception) {
                LumaMod.LOGGER.warn("Failed to finalize idle session for {}", projectId, exception);
            }
        }
    }

    public void flushAll(MinecraftServer server) {
        for (String projectId : this.workingDrafts.activeProjectIds()) {
            try {
                this.serverThreadExecutor.call(server, () -> {
                    TrackedProject trackedProject = this.findTrackedProject(server, projectId);
                    CaptureSessionState sessionState = this.workingDrafts.session(projectId);
                    if (trackedProject != null && sessionState != null) {
                        this.reconcileSession(server, trackedProject, sessionState, false);
                        if (sessionState.hasPendingReconciliation()) {
                            LumaMod.LOGGER.info(
                                    "Skipped final shutdown stabilization for project {} with {} pending dirty chunks",
                                    trackedProject.project().name(),
                                    sessionState.pendingReconcileChunks().size()
                            );
                        }
                    }
                    return this.workingDrafts.freezeForShutdownAfterReconciliation(projectId, trackedProject);
                });
            } catch (IOException exception) {
                LumaMod.LOGGER.warn("Failed to flush session for {}", projectId, exception);
            }
        }
    }

    /**
     * Reconciles already-dirty causal chunks before live undo/redo chooses the
     * next action, so settled fluid and falling-block fallout can join it.
     */
    public void drainUndoRedoStabilization(MinecraftServer server, String projectId) throws IOException {
        this.serverThreadExecutor.run(server, () -> this.drainUndoRedoStabilizationOnServerThread(server, projectId));
    }

    public boolean hasPendingUndoRedoStabilization(MinecraftServer server, String projectId) throws IOException {
        return this.serverThreadExecutor.call(server, () -> {
            if (projectId == null || projectId.isBlank()) {
                return false;
            }
            CaptureSessionState sessionState = this.workingDrafts.session(projectId);
            return sessionState != null && sessionState.hasPendingReconciliation();
        });
    }

    public void invalidateProjectCache(MinecraftServer server) {
        this.trackedProjectCatalog.invalidate(server);
    }

    private void drainUndoRedoStabilizationOnServerThread(MinecraftServer server, String projectId) throws IOException {
        if (projectId == null || projectId.isBlank()) {
            return;
        }

        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        CaptureSessionState sessionState = this.workingDrafts.session(projectId);
        if (trackedProject == null || sessionState == null || !sessionState.hasPendingReconciliation()) {
            return;
        }

        ServerLevel level = this.resolveProjectLevel(server, trackedProject.project());
        this.loadUndoRedoStabilizationChunks(level, trackedProject.project(), sessionState);
        this.reconcileSession(server, trackedProject, sessionState, false);
    }

    private void loadUndoRedoStabilizationChunks(
            ServerLevel level,
            BuildProject project,
            CaptureSessionState sessionState
    ) {
        if (level == null || project == null || sessionState == null) {
            return;
        }
        List<ChunkPoint> pendingChunks = sessionState.pendingReconcileChunks();
        if (pendingChunks.isEmpty()) {
            return;
        }
        int loaded = 0;
        int alreadyLoaded = 0;
        for (ChunkPoint chunk : pendingChunks) {
            if (chunk == null) {
                continue;
            }
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
                alreadyLoaded += 1;
                continue;
            }
            if (level.getChunk(chunk.x(), chunk.z()) != null) {
                loaded += 1;
            }
        }
        if (loaded > 0) {
            LumaMod.LOGGER.info(
                    "Loaded {} deferred stabilization chunks for undo/redo in project {} ({} already loaded)",
                    loaded,
                    project.name(),
                    alreadyLoaded
            );
        }
        LumaDebugLog.log(
                project,
                "capture",
                "Undo/redo stabilization chunk load pending={} loaded={} alreadyLoaded={}",
                pendingChunks.size(),
                loaded,
                alreadyLoaded
        );
    }

    private boolean canUseMutationSource(MinecraftServer server, io.github.luma.domain.model.WorldMutationSource source) {
        return canUseMutationSource(
                server != null && server.isDedicatedServer(),
                WorldMutationContext.currentAccessAllowed(),
                source
        );
    }

    static boolean canUseMutationSource(
            boolean dedicatedServer,
            boolean accessAllowed,
            io.github.luma.domain.model.WorldMutationSource source
    ) {
        return ELIGIBILITY.canUseMutationSource(dedicatedServer, accessAllowed, source);
    }

    private BlockPos entityMutationPos(EntityPayload oldPayload, EntityPayload newPayload) {
        if (newPayload != null) {
            return newPayload.blockPos();
        }
        return oldPayload == null ? BlockPos.ZERO : oldPayload.blockPos();
    }

    private List<BlockPos> entityMutationPositions(EntityPayload oldPayload, EntityPayload newPayload) {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        if (oldPayload != null) {
            positions.add(oldPayload.blockPos());
        }
        if (newPayload != null) {
            positions.add(newPayload.blockPos());
        }
        if (positions.isEmpty()) {
            positions.add(BlockPos.ZERO);
        }
        return List.copyOf(positions);
    }

    private List<ChunkPoint> entityMutationChunks(List<BlockPos> positions) {
        LinkedHashSet<ChunkPoint> chunks = new LinkedHashSet<>();
        for (BlockPos pos : positions == null ? List.<BlockPos>of() : positions) {
            chunks.add(ChunkPoint.from(pos));
        }
        return List.copyOf(chunks);
    }

    private List<TrackedProject> matchingEntityProjects(ServerLevel level, List<BlockPos> positions) throws IOException {
        LinkedHashMap<String, TrackedProject> projects = new LinkedHashMap<>();
        for (BlockPos pos : positions == null ? List.<BlockPos>of() : positions) {
            for (TrackedProject trackedProject : this.matchingProjects(level, pos)) {
                projects.putIfAbsent(trackedProject.project().id().toString(), trackedProject);
            }
        }
        return List.copyOf(projects.values());
    }

    private boolean ensureTrackedEntityChunks(
            TrackedProject trackedProject,
            ServerLevel level,
            List<BlockPos> positions,
            StoredEntityChange capturedChange,
            io.github.luma.domain.model.WorldMutationSource source,
            CaptureSessionState existingSession,
            Instant now
    ) throws IOException {
        for (BlockPos mutationPos : positions == null ? List.<BlockPos>of() : positions) {
            ChunkPoint chunk = ChunkPoint.from(mutationPos);
            boolean activeSessionRegion = this.activeSessionRegionPolicy.contains(level, existingSession, chunk);
            if (!this.ensureTrackedChunk(
                    trackedProject,
                    level,
                    mutationPos,
                    null,
                    null,
                    capturedChange.oldValue(),
                    capturedChange.newValue(),
                    source,
                    activeSessionRegion,
                    now
            )) {
                return false;
            }
        }
        return true;
    }

    private TrackedChangeBuffer getOrCreateWorkingDraft(
            TrackedProject trackedProject,
            io.github.luma.domain.model.WorldMutationSource source,
            Instant now
    ) throws IOException {
        return this.workingDrafts.getOrCreate(trackedProject, source, now);
    }

    private TrackedProject findTrackedProject(MinecraftServer server, String projectId) throws IOException {
        return this.trackedProjectCatalog.find(server, projectId);
    }

    private List<TrackedProject> matchingProjects(ServerLevel level, BlockPos pos) throws IOException {
        return this.trackedProjectCatalog.matching(level, pos);
    }

    private void captureChunkBaseline(
            TrackedProject trackedProject,
            ServerLevel level,
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            Instant now
    ) throws IOException {
        this.captureChunkBaseline(trackedProject, level, pos, oldState, oldBlockEntity, null, null, now);
    }

    private void captureChunkBaseline(
            TrackedProject trackedProject,
            ServerLevel level,
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            EntityPayload oldEntityPayload,
            EntityPayload newEntityPayload,
            Instant now
    ) throws IOException {
        ChunkPoint chunk = new ChunkPoint(pos.getX() >> 4, pos.getZ() >> 4);
        if (this.baselineChunkRepository.contains(trackedProject.layout(), chunk)) {
            LumaDebugLog.log(
                    trackedProject.project(),
                    "capture",
                    "Baseline chunk {}:{} already captured for project {}",
                    chunk.x(),
                    chunk.z(),
                    trackedProject.project().name()
            );
            return;
        }
        if (this.persistenceCoordinator.hasPendingBaselineWrite(trackedProject.project().id().toString(), chunk)) {
            LumaDebugLog.log(
                    trackedProject.project(),
                    "capture",
                    "Baseline chunk {}:{} already queued for project {}",
                    chunk.x(),
                    chunk.z(),
                    trackedProject.project().name()
            );
            return;
        }

        ChunkSnapshotPayload chunkSnapshot = this.chunkSnapshotCaptureService.captureLoadedChunk(
                        level,
                        chunk,
                        pos,
                        oldState,
                        oldBlockEntity,
                        oldEntityPayload,
                        newEntityPayload
                )
                .orElseThrow(() -> new IOException(
                        "Chunk %d:%d is not available for baseline capture in %s".formatted(
                                chunk.x(),
                                chunk.z(),
                                level.dimension().identifier()
                        )
                ));
        this.persistenceCoordinator.enqueueBaselineWrite(
                trackedProject.layout(),
                trackedProject.project().id().toString(),
                trackedProject.project().name(),
                chunkSnapshot,
                now
        );
        LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                "Queued missing baseline chunk {}:{} for project {} from mutation at {}",
                chunk.x(),
                chunk.z(),
                trackedProject.project().name(),
                pos
        );
    }

    private boolean ensureTrackedChunk(
            TrackedProject trackedProject,
            ServerLevel level,
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            io.github.luma.domain.model.WorldMutationSource source,
            boolean activeSessionRegion,
            Instant now
    ) throws IOException {
        return this.ensureTrackedChunk(
                trackedProject,
                level,
                pos,
                oldState,
                oldBlockEntity,
                null,
                null,
                source,
                activeSessionRegion,
                now
        );
    }

    private boolean ensureTrackedChunk(
            TrackedProject trackedProject,
            ServerLevel level,
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            EntityPayload oldEntityPayload,
            EntityPayload newEntityPayload,
            io.github.luma.domain.model.WorldMutationSource source,
            boolean activeSessionRegion,
            Instant now
    ) throws IOException {
        ChunkPoint chunk = new ChunkPoint(pos.getX() >> 4, pos.getZ() >> 4);
        CaptureSessionState session = this.workingDrafts.session(trackedProject.project().id().toString());
        if (session != null && session.hasBaselineChunk(chunk)) {
            return true;
        }
        if (this.persistenceCoordinator.hasPendingBaselineWrite(trackedProject.project().id().toString(), chunk)) {
            return true;
        }
        if (this.baselineChunkRepository.contains(trackedProject.layout(), chunk)) {
            return true;
        }
        if (!ELIGIBILITY.allowsTrackedChunkExpansion(source, activeSessionRegion)) {
            this.diagnosticsLogger.logSkippedCapture(
                    trackedProject,
                    source,
                    pos,
                    "untracked-chunk-source-cannot-expand",
                    "chunk " + chunk.x() + ":" + chunk.z()
                            + " is not tracked yet and the source cannot expand tracking"
            );
            return false;
        }

        this.captureChunkBaseline(
                trackedProject,
                level,
                pos,
                oldState,
                oldBlockEntity,
                oldEntityPayload,
                newEntityPayload,
                now
        );
        return true;
    }

    private boolean canCaptureIntoSession(
            TrackedProject trackedProject,
            ServerLevel level,
            io.github.luma.domain.model.WorldMutationSource source,
            BlockPos pos
    ) {
        String projectId = trackedProject.project().id().toString();
        if (this.workingDrafts.hasBuffer(projectId)) {
            if (!ELIGIBILITY.canUseDirectCapture(source, WorldMutationContext.currentActionId())) {
                this.diagnosticsLogger.logSkippedCapture(
                        trackedProject,
                        source,
                        pos,
                        "no-causal-action",
                        "no causal action is active"
                );
                return false;
            }
            if (!requiresActiveRegionMembership(source)) {
                return true;
            }
            ChunkPoint chunk = ChunkPoint.from(pos);
            CaptureSessionState sessionState = this.workingDrafts.session(projectId);
            if (this.activeSessionRegionPolicy.contains(level, sessionState, chunk)) {
                return true;
            }
            this.diagnosticsLogger.logSkippedCapture(
                    trackedProject,
                    source,
                    pos,
                    "outside-active-session-region",
                    "chunk " + chunk.x() + ":" + chunk.z() + " is outside the active session region"
            );
            return false;
        }
        if (allowsSessionBootstrap(source)) {
            return true;
        }
        this.diagnosticsLogger.logSkippedCapture(
                trackedProject,
                source,
                pos,
                "no-active-session-source-cannot-bootstrap",
                "no active session exists and the source cannot bootstrap capture"
        );
        return false;
    }

    private void recordDeferredBlockMutation(
            TrackedProject trackedProject,
            ServerLevel level,
            io.github.luma.domain.model.WorldMutationSource source,
            BlockPos pos,
            ChunkPoint chunk,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            Instant now,
            CaptureSessionState.DeferredActionContext deferredActionContext
    ) throws IOException {
        String projectId = trackedProject.project().id().toString();
        TrackedChangeBuffer buffer = this.getOrCreateWorkingDraft(trackedProject, source, now);
        CaptureSessionState session = this.workingDrafts.session(projectId);
        if (session == null) {
            return;
        }
        if (ELIGIBILITY.isExplicitRootSource(source)) {
            session.addRootChunk(chunk);
        }
        this.baselineCoordinator.recordBaselineCorrection(session, pos, oldState, oldBlockEntity);
        this.baselineCoordinator.captureSessionChunkBaseline(
                trackedProject,
                level,
                session,
                chunk,
                pos,
                oldState,
                oldBlockEntity
        );
        session.markDirtySection(
                new ChunkSectionPoint(chunk, Math.floorDiv(pos.getY(), 16)),
                deferredActionContext,
                level.getGameTime()
        );
        this.workingDrafts.markDirty(projectId);
        CaptureSessionDiagnostics diagnostics = this.workingDrafts.diagnosticsForSession(projectId);
        diagnostics.record(
                source,
                pos,
                oldState,
                level.getBlockState(pos),
                oldBlockEntity != null,
                level.getBlockEntity(pos) != null
        );
        LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                "Deferred {} mutation at {} for project {} into stabilization chunk {}:{} with pending buffer size {}",
                source,
                pos,
                trackedProject.project().name(),
                chunk.x(),
                chunk.z(),
                buffer.size()
        );
        this.historyDebugLog.logDeferredBlock(
                trackedProject.project(),
                source,
                pos,
                oldState,
                level.getBlockState(pos),
                buffer.size(),
                actionId(deferredActionContext),
                actor(deferredActionContext)
        );
    }

    private void reconcileSession(
            MinecraftServer server,
            TrackedProject trackedProject,
            CaptureSessionState session,
            boolean finalDrain
    ) throws IOException {
        ServerLevel level = this.resolveProjectLevel(server, trackedProject.project());
        if (level == null) {
            return;
        }
        SessionStabilizationService.ReconciliationResult result;
        try {
            result = this.stabilizationService.stabilizePendingChunks(
                    level,
                    trackedProject.project(),
                    session,
                    finalDrain
            );
        } catch (IllegalStateException exception) {
            throw new IOException(
                    "Failed to stabilize dirty chunks for project "
                            + trackedProject.project().name()
                            + ": "
                            + exception.getMessage(),
                    exception
            );
        }

        if (finalDrain && session.hasPendingReconciliation()) {
            LumaMod.LOGGER.info(
                    "Skipped final stabilization for project {} with {} pending dirty chunks",
                    trackedProject.project().name(),
                    session.pendingReconcileChunks().size()
            );
        }
        if (result.inFlight()) {
            return;
        }
        String projectId = trackedProject.project().id().toString();
        boolean liveReconciled = finalDrain
                && this.reconcileWorkingDraftAgainstLiveWorld(level, trackedProject, session, Instant.now());
        if (result.chunkCount() > 0) {
            this.diagnosticsLogger.logReconciliation(trackedProject, result);
            this.liveUndoRedoActionRecorder.recordReconciledChanges(trackedProject, level, result, Instant.now());
        }
        if (session.buffer().isEmpty()) {
            this.workingDrafts.discardIfEmpty(trackedProject, "after reconciliation");
        } else if (liveReconciled) {
            this.workingDrafts.markDirty(projectId);
        }
    }

    private boolean reconcileWorkingDraftAgainstLiveWorld(
            ServerLevel level,
            TrackedProject trackedProject,
            CaptureSessionState session,
            Instant now
    ) {
        if (level == null || trackedProject == null || session == null || session.buffer().isEmpty()) {
            return false;
        }
        boolean blocksChanged = this.reconcileWorkingDraftBlocksAgainstLiveWorld(level, session, now);
        boolean entitiesChanged = this.reconcileWorkingDraftEntitiesAgainstLiveWorld(level, session, now);
        boolean changed = blocksChanged || entitiesChanged;
        if (changed) {
            LumaMod.LOGGER.info(
                    "Reconciled working draft for project {} against live world; pending={}",
                    trackedProject.project().name(),
                    session.buffer().size()
            );
        }
        return changed;
    }

    private boolean reconcileWorkingDraftBlocksAgainstLiveWorld(
            ServerLevel level,
            CaptureSessionState session,
            Instant now
    ) {
        LinkedHashSet<ChunkPoint> loadedChunks = new LinkedHashSet<>();
        List<StoredBlockChange> liveTargets = new ArrayList<>();
        for (StoredBlockChange change : session.buffer().orderedChanges()) {
            ChunkPoint chunk = ChunkPoint.from(change.pos());
            if (!this.isChunkLoaded(level, chunk)) {
                continue;
            }
            loadedChunks.add(chunk);
            liveTargets.add(change.withLatestState(this.liveStatePayload(level, change.pos().toBlockPos())));
        }
        return this.liveStateReconciler.reconcileLoadedBlocks(session, loadedChunks, liveTargets, now);
    }

    private boolean reconcileWorkingDraftEntitiesAgainstLiveWorld(
            ServerLevel level,
            CaptureSessionState session,
            Instant now
    ) {
        List<StoredEntityChange> liveTargets = new ArrayList<>();
        for (StoredEntityChange change : session.buffer().orderedEntityChanges()) {
            Optional<UUID> entityId = this.entityUuid(change);
            if (entityId.isEmpty()) {
                continue;
            }
            Entity entity = level.getEntity(entityId.get());
            EntityPayload livePayload = entity == null ? null : this.entitySnapshotService.capture(level, entity);
            if (entity != null && livePayload == null) {
                continue;
            }
            liveTargets.add(new StoredEntityChange(
                    change.entityId(),
                    change.entityType(),
                    change.oldValue(),
                    livePayload
            ));
        }
        return this.liveStateReconciler.reconcileEntities(session, liveTargets, now);
    }

    private StatePayload liveStatePayload(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        CompoundTag blockEntityTag = null;
        if (state.hasBlockEntity()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            blockEntityTag = BlockEntitySnapshot.capture(level, blockEntity);
        }
        return StatePayload.capture(state, blockEntityTag);
    }

    private boolean isChunkLoaded(ServerLevel level, ChunkPoint chunk) {
        return level != null
                && chunk != null
                && level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null;
    }

    private Optional<UUID> entityUuid(StoredEntityChange change) {
        if (change == null) {
            return Optional.empty();
        }
        if (change.newValue() != null) {
            Optional<UUID> id = change.newValue().uuid();
            if (id.isPresent()) {
                return id;
            }
        }
        if (change.oldValue() != null) {
            Optional<UUID> id = change.oldValue().uuid();
            if (id.isPresent()) {
                return id;
            }
        }
        try {
            return change.entityId() == null || change.entityId().isBlank()
                    ? Optional.empty()
                    : Optional.of(UUID.fromString(change.entityId()));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private CaptureSessionState.DeferredActionContext deferredActionContext(
            CaptureSessionState session,
            ChunkPoint chunk,
            io.github.luma.domain.model.WorldMutationSource source
    ) {
        CaptureSessionState.DeferredActionContext currentContext = this.currentDeferredActionContext(source);
        if (currentContext != null) {
            return currentContext;
        }
        if (!ELIGIBILITY.canReuseDeferredActionContext(source) || session == null) {
            return null;
        }
        return session.deferredActionContext(chunk);
    }

    private CaptureSessionState.DeferredActionContext currentDeferredActionContext(
            io.github.luma.domain.model.WorldMutationSource source
    ) {
        String actionId = WorldMutationContext.currentActionId();
        if (actionId == null || actionId.isBlank()) {
            return null;
        }
        return new CaptureSessionState.DeferredActionContext(
                actionId,
                WorldMutationContext.currentActor(),
                WorldMutationContext.currentAccessAllowed(),
                ELIGIBILITY.hiddenInBuilderSurfaces(source)
        );
    }

    private static String actionId(CaptureSessionState.DeferredActionContext context) {
        return context == null ? "" : context.actionId();
    }

    private static String actor(CaptureSessionState.DeferredActionContext context) {
        return context == null ? "" : context.actor();
    }

    private ServerLevel resolveProjectLevel(MinecraftServer server, BuildProject project) {
        if (server == null || project == null || project.dimensionId() == null || project.dimensionId().isBlank()) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (project.dimensionId().equals(level.dimension().identifier().toString())) {
                return level;
            }
        }
        return null;
    }

    public static boolean shouldCaptureMutation(io.github.luma.domain.model.WorldMutationSource source) {
        if (WorldMutationContext.captureSuppressed()) {
            return false;
        }
        return ELIGIBILITY.shouldCaptureMutation(source);
    }

    public static boolean allowsAutomaticProjectCreation(io.github.luma.domain.model.WorldMutationSource source) {
        return ELIGIBILITY.allowsAutomaticProjectCreation(source);
    }

    public static boolean allowsSessionBootstrap(io.github.luma.domain.model.WorldMutationSource source) {
        return ELIGIBILITY.allowsSessionBootstrap(source);
    }

    public static boolean allowsTrackedChunkExpansion(io.github.luma.domain.model.WorldMutationSource source) {
        return ELIGIBILITY.allowsTrackedChunkExpansion(source);
    }

    static boolean requiresActiveRegionMembership(io.github.luma.domain.model.WorldMutationSource source) {
        return ELIGIBILITY.requiresActiveRegionMembership(source);
    }

    static boolean isWithinChunkRadius(ChunkPoint first, ChunkPoint second, int radius) {
        if (first == null || second == null || radius < 0) {
            return false;
        }
        return Math.abs(first.x() - second.x()) <= radius
                && Math.abs(first.z() - second.z()) <= radius;
    }

    public static String defaultActor(io.github.luma.domain.model.WorldMutationSource source) {
        return ELIGIBILITY.defaultActor(source);
    }
}
