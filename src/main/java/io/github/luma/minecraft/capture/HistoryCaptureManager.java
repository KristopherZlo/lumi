package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSectionPoint;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.ProjectDirtyScope;
import io.github.luma.domain.model.RecoveryDraft;
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
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Captures tracked world edits and fans them out into project-scoped runtime models.
 *
 * <p>This manager coordinates project matching, capture policy, stabilization,
 * and baseline chunk capture. Durable working-draft ownership lives in
 * {@link WorkingDraftSessionManager}.
 */
public final class HistoryCaptureManager {

    private static final Duration ACTIVE_DRAFT_FLUSH_INTERVAL = Duration.ofSeconds(3);
    private static final int IDLE_FLUSH_TICK_INTERVAL = 5;
    private static final CaptureEligibilityService ELIGIBILITY = new CaptureEligibilityService();
    private static final HistoryCaptureManager INSTANCE = new HistoryCaptureManager();

    private final HistoryDebugLog historyDebugLog = new HistoryDebugLog();
    private final CaptureDiagnosticsLogger diagnosticsLogger = new CaptureDiagnosticsLogger();
    private final BlockMutationCaptureGate blockMutationGate =
            new BlockMutationCaptureGate(ELIGIBILITY, this.diagnosticsLogger);
    private final CapturePersistenceCoordinator persistenceCoordinator = new CapturePersistenceCoordinator();
    private final WorkingDraftSessionManager workingDrafts = new WorkingDraftSessionManager(this.persistenceCoordinator);
    private final ProjectDirtyScopeManager projectDirtyScopes =
            new ProjectDirtyScopeManager(this.persistenceCoordinator);
    private final ProjectService projectService = new ProjectService();
    private final ActiveWorkZoneTouchRecorder activeWorkZoneTouchRecorder = new ActiveWorkZoneTouchRecorder();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final TrackedProjectCatalog trackedProjectCatalog = new TrackedProjectCatalog(
            this.projectService,
            this.projectRepository,
            this.variantRepository
    );
    private final EntityChangeCapturePlanner entityCapturePlanner =
            new EntityChangeCapturePlanner(this.trackedProjectCatalog);
    private final BaselineChunkRepository baselineChunkRepository = new BaselineChunkRepository();
    private final SessionStabilizationService stabilizationService = new SessionStabilizationService();
    private final CaptureBaselineCoordinator baselineCoordinator =
            new CaptureBaselineCoordinator(this.stabilizationService, new PersistentBlockStatePolicy());
    private final LiveBlockSectionReconciliationMarker liveBlockSectionReconciliationMarker =
            new LiveBlockSectionReconciliationMarker(this.baselineCoordinator, this.workingDrafts);
    private final SessionDraftBlockChangeRecorder draftBlockChangeRecorder = new SessionDraftBlockChangeRecorder();
    private final LiveUndoRedoActionRecorder liveUndoRedoActionRecorder = new LiveUndoRedoActionRecorder();
    private final ChunkSnapshotCaptureService chunkSnapshotCaptureService = new ChunkSnapshotCaptureService();
    private final EntitySnapshotService entitySnapshotService = new EntitySnapshotService();
    private final WorkingDraftLiveStateReconciler liveStateReconciler = new WorkingDraftLiveStateReconciler();
    private final ServerThreadExecutor serverThreadExecutor = new ServerThreadExecutor();
    private final ActiveSessionRegionPolicy activeSessionRegionPolicy = new ActiveSessionRegionPolicy();
    private final CaptureAccessGuard accessGuard = new CaptureAccessGuard(ELIGIBILITY);
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
     * Captures the active-session baseline and marks the section dirty before
     * any persistent mutation can trigger synchronous fallout.
     */
    public void capturePreMutationBaseline(
            ServerLevel level,
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity
    ) {
        io.github.luma.domain.model.WorldMutationSource source = WorldMutationContext.currentSource();
        boolean explicitRootSource = ELIGIBILITY.isExplicitRootSource(source);
        if (level == null || pos == null
                || !shouldTrackPersistentMutation(source)) {
            return;
        }

        try {
            Instant now = Instant.now();
            List<TrackedProject> matchingProjects = this.matchingProjects(level, pos);
            if (matchingProjects.isEmpty()) {
                if (!explicitRootSource
                        || !allowsAutomaticProjectCreation(source)
                        || !this.accessGuard.canUseMutationSource(level.getServer(), source)
                        || !this.accessGuard.canCreateProjectInCurrentMode()) {
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
                ChunkSnapshotPayload projectBaseline = null;
                if (!this.baselineChunkRepository.contains(trackedProject.layout(), chunk)
                        && !this.persistenceCoordinator.hasPendingBaselineWrite(projectId, chunk)) {
                    projectBaseline = this.captureChunkBaseline(
                            trackedProject,
                            level,
                            pos,
                            oldState,
                            oldBlockEntity,
                            now
                    );
                }
                boolean hiddenReconciliation = this.hiddenReconciliation(existingSession, chunk, source);
                if (!this.canCaptureIntoSession(trackedProject, level, source, pos)) {
                    continue;
                }
                this.getOrCreateWorkingDraft(trackedProject, source, now);
                CaptureSessionState session = this.workingDrafts.session(projectId);
                if (session == null) {
                    continue;
                }
                if (!session.hasBaselineChunk(chunk)
                        && !this.persistenceCoordinator.hasPendingBaselineWrite(projectId, chunk)
                        && !this.baselineChunkRepository.contains(trackedProject.layout(), chunk)) {
                    projectBaseline = this.captureChunkBaseline(
                            trackedProject,
                            level,
                            pos,
                            oldState,
                            oldBlockEntity,
                            now
                    );
                }
                this.baselineCoordinator.captureSessionChunkBaseline(session, chunk, projectBaseline);
                this.liveBlockSectionReconciliationMarker.mark(
                        trackedProject,
                        level,
                        source,
                        pos,
                        chunk,
                        oldState,
                        oldBlockEntity,
                        hiddenReconciliation,
                        explicitRootSource
                );
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to capture pre-mutation baseline at {} in {}", pos, level.dimension().identifier(), exception);
        }
    }

    public void recordPersistentBlockMutation(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !shouldTrackPersistentMutation(WorldMutationContext.currentSource())) {
            return;
        }
        try {
            ChunkSectionPoint section = ChunkSectionPoint.from(io.github.luma.domain.model.BlockPoint.from(pos));
            for (TrackedProject project : this.matchingProjects(level, pos)) {
                this.projectDirtyScopes.markBlockSection(project, section);
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to mark persistent mutation dirty at {} in {}", pos, level.dimension().identifier(), exception);
        }
    }

    public void recordPersistentBlockMutations(ServerLevel level, List<BlockPos> positions) {
        if (level == null || positions == null || positions.isEmpty()
                || !shouldTrackPersistentMutation(WorldMutationContext.currentSource())) {
            return;
        }
        try {
            Map<TrackedProject, LinkedHashSet<ChunkSectionPoint>> sectionsByProject = new LinkedHashMap<>();
            for (BlockPos pos : positions) {
                if (pos == null) {
                    continue;
                }
                ChunkSectionPoint section = ChunkSectionPoint.from(io.github.luma.domain.model.BlockPoint.from(pos));
                for (TrackedProject project : this.matchingProjects(level, pos)) {
                    sectionsByProject.computeIfAbsent(project, ignored -> new LinkedHashSet<>()).add(section);
                }
            }
            sectionsByProject.forEach(this.projectDirtyScopes::markBlockSections);
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to bulk-mark persistent mutations dirty in {}", level.dimension().identifier(), exception);
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
        if (level == null
                || !shouldCaptureMutation(source)
                || !this.accessGuard.canUseMutationSource(level.getServer(), source)) {
            return;
        }

        try {
            Instant now = Instant.now();
            WorldMutationCapturePolicy.CaptureResult captureResult = null;
            List<TrackedProject> matchingProjects = this.matchingProjects(level, pos);
            if (matchingProjects.isEmpty()) {
                if (!allowsAutomaticProjectCreation(source) || !this.accessGuard.canCreateProjectInCurrentMode()) {
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
                if (!this.accessGuard.canUseProjectInCurrentMode(trackedProject)) { continue; }
                String projectId = trackedProject.project().id().toString();
                CaptureSessionState existingSession = this.workingDrafts.session(projectId);
                ChunkPoint chunk = ChunkPoint.from(pos);
                boolean activeSessionRegion = this.activeSessionRegionPolicy.contains(level, existingSession, chunk);
                boolean causalAction = this.hasActiveCausalAction(projectId);
                boolean hiddenReconciliation = this.hiddenReconciliation(existingSession, chunk, source);
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
                        causalAction
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
                                causalAction
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
                this.liveUndoRedoActionRecorder.recordBlock(trackedProject, level, capturedChange, now);
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
                            hiddenReconciliation
                    );
                    continue;
                }
                TrackedChangeBuffer buffer = this.getOrCreateWorkingDraft(trackedProject, source, now);
                CaptureSessionState session = this.workingDrafts.session(projectId);
                if (session == null) {
                    continue;
                }
                if (usesDeferredStabilization) {
                    this.recordDeferredBlockMutation(
                            trackedProject,
                            level,
                            source,
                            pos,
                            chunk,
                            mutation.oldState(),
                            mutation.oldBlockEntity(),
                            now,
                            hiddenReconciliation
                    );
                    continue;
                }
                if (ELIGIBILITY.usesLiveStateReconciliation(source)) {
                    this.liveBlockSectionReconciliationMarker.mark(
                            trackedProject, level, source, pos, chunk, mutation.oldState(), mutation.oldBlockEntity(),
                            hiddenReconciliation, ELIGIBILITY.isExplicitRootSource(source));
                }
                SessionDraftBlockChangeRecorder.Result draftRecord =
                        this.draftBlockChangeRecorder.record(session, buffer, capturedChange, now);
                this.activeWorkZoneTouchRecorder.record(trackedProject, capturedChange, now);
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
        if (level == null || changes == null || changes.isEmpty()) {
            return;
        }
        this.recordPersistentBlockMutations(level, changes.stream().map(BlockChangeInput::pos).toList());
        if (!shouldCaptureMutation(source)
                || !this.accessGuard.canUseMutationSource(level.getServer(), source)) {
            return;
        }

        try {
            Instant now = Instant.now();
            boolean autoWorkspaceCreated = false;
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
                            autoWorkspaceCreated
                    );
                } catch (Exception exception) {
                    LumaMod.LOGGER.warn("Failed to capture bulk block change at {} in {}", input.pos(), level.dimension().identifier(), exception);
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
            boolean autoWorkspaceCreated
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
                && allowsAutomaticProjectCreation(source)
                && this.accessGuard.canCreateProjectInCurrentMode()) {
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
            if (!this.accessGuard.canUseProjectInCurrentMode(trackedProject)) { continue; }
            this.recordBulkBlockChangeForProject(
                    level,
                    trackedProject,
                    source,
                    now,
                    input,
                    captureResult
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
            WorldMutationCapturePolicy.CaptureResult captureResult
    ) throws IOException {
        String projectId = trackedProject.project().id().toString();
        CaptureSessionState existingSession = this.workingDrafts.session(projectId);
        ChunkPoint chunk = ChunkPoint.from(input.pos());
        boolean activeSessionRegion = this.activeSessionRegionPolicy.contains(level, existingSession, chunk);
        boolean causalAction = this.hasActiveCausalAction(projectId);
        boolean hiddenReconciliation = this.hiddenReconciliation(existingSession, chunk, source);
        boolean usesDeferredStabilization = ELIGIBILITY.usesDeferredStabilization(trackedProject.project(), source);
        if (usesDeferredStabilization
                && !ELIGIBILITY.canUseDeferredStabilization(
                        trackedProject.project(),
                        source,
                        activeSessionRegion,
                        causalAction
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
        StoredBlockChange capturedChange = mutation == null ? null : mutation.change();
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
        this.liveUndoRedoActionRecorder.recordBlock(trackedProject, level, capturedChange, now);
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
                    hiddenReconciliation
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
        if (usesDeferredStabilization) {
            this.recordDeferredBlockMutation(
                    trackedProject,
                    level,
                    source,
                    input.pos(),
                    chunk,
                    mutation.oldState(),
                    mutation.oldBlockEntity(),
                    now,
                    hiddenReconciliation
            );
            return;
        }
        if (ELIGIBILITY.usesLiveStateReconciliation(source)) {
            this.liveBlockSectionReconciliationMarker.mark(
                    trackedProject, level, source, input.pos(), chunk, mutation.oldState(), mutation.oldBlockEntity(),
                    hiddenReconciliation, ELIGIBILITY.isExplicitRootSource(source));
        }

        SessionDraftBlockChangeRecorder.Result draftRecord =
                this.draftBlockChangeRecorder.record(session, buffer, capturedChange, now);
        this.activeWorkZoneTouchRecorder.record(trackedProject, capturedChange, now);
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
        if (level == null) {
            return;
        }
        this.recordPersistentEntityMutation(level, oldPayload, newPayload);
        if (!this.accessGuard.canUseMutationSource(level.getServer(), source)) {
            return;
        }

        try {
            EntityChangeCapturePlanner.CapturePlan plan =
                    this.entityCapturePlanner.plan(oldPayload, newPayload, actionStartedAt);
            if (plan == null) {
                return;
            }
            BlockPos pos = plan.primaryPos();
            List<TrackedProject> matchingProjects = this.entityCapturePlanner.matchingProjects(level, plan);
            if (matchingProjects.isEmpty()) {
                if (plan.durableMutation().isEmpty()
                        || !allowsAutomaticProjectCreation(source)
                        || !this.accessGuard.canCreateProjectInCurrentMode()) {
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
                matchingProjects = this.entityCapturePlanner.matchingProjects(level, plan);
                LumaMod.LOGGER.info("Created world workspace automatically for entity mutation in {}", level.dimension().identifier());
            }

            for (TrackedProject trackedProject : matchingProjects) {
                if (!this.accessGuard.canUseProjectInCurrentMode(trackedProject)) { continue; }
                if (plan.liveMutation().isPresent()) {
                    this.liveUndoRedoActionRecorder.recordEntity(
                            trackedProject,
                            level,
                            plan.liveMutation().get(),
                            plan.capturedAt(),
                            plan.actionStartedAt()
                    );
                }
                if (plan.durableMutation().isEmpty()) {
                    continue;
                }
                StoredEntityChange capturedChange = plan.durableMutation().get();
                if (!this.canCaptureIntoSession(trackedProject, level, source, pos)) {
                    continue;
                }
                String projectId = trackedProject.project().id().toString();
                CaptureSessionState existingSession = this.workingDrafts.session(projectId);
                if (!this.ensureTrackedEntityChunks(
                        trackedProject,
                        level,
                        plan.positions(),
                        capturedChange,
                        source,
                        existingSession,
                        plan.capturedAt()
                )) {
                    continue;
                }

                TrackedChangeBuffer buffer = this.getOrCreateWorkingDraft(trackedProject, source, plan.capturedAt());
                CaptureSessionState session = this.workingDrafts.session(projectId);
                if (session != null) {
                    for (ChunkPoint chunk : plan.chunks()) {
                        session.addRootChunk(chunk);
                    }
                }

                int pendingBefore = buffer.size();
                buffer.addEntityChange(capturedChange, plan.capturedAt());
                this.activeWorkZoneTouchRecorder.record(trackedProject, plan.positions(), plan.capturedAt());
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

    private void recordPersistentEntityMutation(
            ServerLevel level,
            EntityPayload oldPayload,
            EntityPayload newPayload
    ) {
        if (!shouldTrackPersistentMutation(WorldMutationContext.currentSource())
                || java.util.Objects.equals(oldPayload, newPayload)) {
            return;
        }
        try {
            Map<TrackedProject, LinkedHashSet<ChunkPoint>> chunksByProject = new LinkedHashMap<>();
            for (EntityPayload payload : new EntityPayload[] {oldPayload, newPayload}) {
                if (payload == null) {
                    continue;
                }
                for (TrackedProject project : this.matchingProjects(level, payload.blockPos())) {
                    chunksByProject.computeIfAbsent(project, ignored -> new LinkedHashSet<>()).add(payload.chunk());
                }
            }
            for (Map.Entry<TrackedProject, LinkedHashSet<ChunkPoint>> entry : chunksByProject.entrySet()) {
                for (ChunkPoint chunk : entry.getValue()) {
                    this.projectDirtyScopes.markEntityChunk(entry.getKey(), chunk);
                }
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to mark persistent entity mutation dirty in {}", level.dimension().identifier(), exception);
        }
    }

    /**
     * Updates the durable working draft after an undo/redo apply, whose world
     * mutations are deliberately capture-suppressed.
     */
    public void applyUndoRedoAdjustments(
            MinecraftServer server,
            String projectId,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            String actor,
            Instant now
    ) throws IOException {
        this.serverThreadExecutor.run(server, () -> {
            if ((changes == null || changes.isEmpty())
                    && (entityChanges == null || entityChanges.isEmpty())) {
                return;
            }
            TrackedProject project = this.findTrackedProject(server, projectId);
            if (project == null) {
                return;
            }

            TrackedChangeBuffer buffer = this.getOrCreateWorkingDraft(
                    project,
                    io.github.luma.domain.model.WorldMutationSource.PLAYER,
                    now
            );
            CaptureSessionState session = this.workingDrafts.session(projectId);
            for (StoredBlockChange change : changes == null ? List.<StoredBlockChange>of() : changes) {
                if (change == null || change.isNoOp()) {
                    continue;
                }
                buffer.addChange(change, now);
                if (session != null) {
                    session.addRootChunk(ChunkPoint.from(change.pos()));
                }
            }
            for (StoredEntityChange change : entityChanges == null ? List.<StoredEntityChange>of() : entityChanges) {
                if (change == null || change.isNoOp()) {
                    continue;
                }
                buffer.addEntityChange(change, now);
                if (session != null) {
                    session.addRootChunk(change.chunk());
                }
            }
            if (buffer.isEmpty()) {
                this.workingDrafts.discardIfEmpty(project, "after undo/redo");
                return;
            }
            this.workingDrafts.resetStabilizationSession(projectId, buffer);
            this.workingDrafts.markDirty(projectId);
            LumaMod.LOGGER.info(
                    "Adjusted working draft for project {} after undo/redo by {}; pending={}",
                    project.project().name(),
                    actor == null || actor.isBlank() ? "player" : actor,
                    buffer.size()
            );
        });
    }

    public void finalizeProjectSession(MinecraftServer server, String projectId) throws IOException {
        this.freezeWorkingDraftForRecovery(server, projectId);
    }

    public Optional<RecoveryDraft> snapshotDraft(MinecraftServer server, String projectId) throws IOException {
        return this.serverThreadExecutor.call(server, () -> this.snapshotDraftOnServerThread(server, projectId));
    }

    /**
     * Loads the current safety scope. Server-thread callers use its coherent
     * runtime copy; operation workers drain and reopen the durable sidecar.
     */
    public ProjectDirtyScope loadProjectDirtyScope(MinecraftServer server, String projectId) throws IOException {
        boolean serverThread = server.isSameThread();
        TrackedProject trackedProject = this.serverThreadExecutor.call(
                server,
                () -> this.findTrackedProject(server, projectId)
        );
        if (trackedProject == null) {
            throw new IllegalArgumentException("Tracked project is missing: " + projectId);
        }
        if (serverThread) {
            return this.projectDirtyScopes.runtimeSnapshot(trackedProject);
        }
        this.persistenceCoordinator.drainProject(projectId, trackedProject.project().name());
        return this.projectDirtyScopes.loadDurable(trackedProject);
    }

    /** Copies the live chunks selected by a previously isolated dirty scope. */
    public List<ChunkSnapshotPayload> captureProjectDirtyScope(
            MinecraftServer server,
            String projectId,
            ProjectDirtyScope dirtyScope
    ) throws IOException {
        return this.serverThreadExecutor.call(
                server,
                () -> this.captureProjectDirtyScopeOnServerThread(server, projectId, dirtyScope)
        );
    }

    /** Replaces the isolated ledger only after the new head is durable. */
    public void completeProjectDirtyScopeSave(
            MinecraftServer server,
            String projectId,
            ProjectDirtyScope expectedScope,
            ProjectDirtyScope remainder,
            String newBaseVersionId
    ) throws IOException {
        TrackedProject trackedProject = this.serverThreadExecutor.call(
                server,
                () -> this.findTrackedProject(server, projectId)
        );
        if (trackedProject == null) {
            throw new IllegalArgumentException("Tracked project is missing: " + projectId);
        }
        this.projectDirtyScopes.replaceAfterCommit(
                trackedProject,
                expectedScope,
                remainder,
                newBaseVersionId
        );
    }

    /** Clears the isolated ledger after a restore has passed final verification. */
    public void completeProjectDirtyScopeRestore(
            MinecraftServer server,
            String projectId,
            ProjectDirtyScope expectedScope,
            String restoredVersionId
    ) throws IOException {
        ProjectDirtyScope empty = ProjectDirtyScope.empty(
                expectedScope.projectId(),
                expectedScope.variantId(),
                expectedScope.baseVersionId()
        );
        this.completeProjectDirtyScopeSave(
                server,
                projectId,
                expectedScope,
                empty,
                restoredVersionId
        );
    }

    private List<ChunkSnapshotPayload> captureProjectDirtyScopeOnServerThread(
            MinecraftServer server,
            String projectId,
            ProjectDirtyScope dirtyScope
    ) throws IOException {
        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        if (trackedProject == null || dirtyScope == null) {
            throw new IllegalArgumentException("Tracked project and dirty scope are required");
        }
        ServerLevel level = this.resolveProjectLevel(server, trackedProject.project());
        if (level == null) {
            throw new IOException("Project dimension is unavailable for dirty reconciliation");
        }

        Map<ChunkPoint, Set<Integer>> sectionsByChunk = new LinkedHashMap<>();
        for (ChunkSectionPoint section : dirtyScope.blockSections()) {
            sectionsByChunk.computeIfAbsent(section.chunk(), ignored -> new LinkedHashSet<>())
                    .add(section.sectionY());
        }
        for (ChunkPoint chunk : dirtyScope.entityChunks()) {
            sectionsByChunk.computeIfAbsent(chunk, ignored -> new LinkedHashSet<>());
        }

        List<ChunkSnapshotPayload> snapshots = new ArrayList<>(sectionsByChunk.size());
        for (Map.Entry<ChunkPoint, Set<Integer>> entry : sectionsByChunk.entrySet()) {
            snapshots.add(this.chunkSnapshotCaptureService.captureDirtyScopeChunk(
                    level,
                    entry.getKey(),
                    entry.getValue(),
                    dirtyScope.entityChunks().contains(entry.getKey())
            ).orElseThrow(() -> new IOException("Dirty chunk is unavailable: " + entry.getKey())));
        }
        return List.copyOf(snapshots);
    }

    public Optional<BuildProject> findWholeDimensionProject(ServerLevel level) throws IOException {
        TrackedProject trackedProject = this.trackedProjectCatalog.findWholeDimension(level);
        return trackedProject == null ? Optional.empty() : Optional.of(trackedProject.project());
    }

    public boolean activeDraftUpdatedAfter(MinecraftServer server, String projectId, Instant threshold) throws IOException {
        return this.serverThreadExecutor.call(
                server,
                () -> this.workingDrafts.activeDraftUpdatedAfter(projectId, threshold)
        );
    }

    public boolean hasInterruptedDraft(MinecraftServer server, String projectId) throws IOException {
        return this.serverThreadExecutor.call(server, () -> this.hasInterruptedDraftOnServerThread(server, projectId));
    }

    public void markPersistedDraftCurrentRun(MinecraftServer server, String projectId) throws IOException {
        this.serverThreadExecutor.run(server, () -> this.workingDrafts.markPersistedDraftCurrentRun(projectId, this.findTrackedProject(server, projectId)));
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
        EntityMutationTracker.drainPendingSpawns(server);
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
        EntityMutationTracker.drainPendingSpawns(server);
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
                        this.reconcileSession(server, trackedProject, sessionState, true);
                    }
                    return this.workingDrafts.freezeForShutdownAfterReconciliation(projectId, trackedProject);
                });
            } catch (IOException exception) {
                LumaMod.LOGGER.warn("Failed to flush session for {}", projectId, exception);
            }
        }
        try {
            this.projectDirtyScopes.drainAll();
        } catch (IOException exception) {
            LumaMod.LOGGER.warn("Failed to flush project dirty scopes during shutdown", exception);
        }
    }

    public void invalidateProjectCache(MinecraftServer server) {
        this.trackedProjectCatalog.invalidate(server);
    }

    static boolean canUseMutationSource(
            boolean dedicatedServer,
            boolean accessAllowed,
            io.github.luma.domain.model.WorldMutationSource source
    ) {
        return ELIGIBILITY.canUseMutationSource(dedicatedServer, accessAllowed, source);
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
        return this.workingDrafts.getOrCreate(
                trackedProject,
                source,
                WorldMutationContext.currentActor(),
                now
        );
    }

    private boolean hasActiveCausalAction(String projectId) {
        return WorldMutationContext.hasCausalAction()
                && !UndoRedoHistoryManager.getInstance().hasRedoAction(
                        projectId,
                        WorldMutationContext.currentActionId()
                );
    }

    private TrackedProject findTrackedProject(MinecraftServer server, String projectId) throws IOException {
        return this.trackedProjectCatalog.find(server, projectId);
    }

    private List<TrackedProject> matchingProjects(ServerLevel level, BlockPos pos) throws IOException {
        return this.trackedProjectCatalog.matching(level, pos);
    }

    private ChunkSnapshotPayload captureChunkBaseline(
            TrackedProject trackedProject, ServerLevel level, BlockPos pos,
            BlockState oldState, CompoundTag oldBlockEntity, Instant now
    ) throws IOException {
        return this.captureChunkBaseline(trackedProject, level, pos, oldState, oldBlockEntity, null, null, now);
    }

    private ChunkSnapshotPayload captureChunkBaseline(
            TrackedProject trackedProject, ServerLevel level, BlockPos pos,
            BlockState oldState, CompoundTag oldBlockEntity,
            EntityPayload oldEntityPayload, EntityPayload newEntityPayload, Instant now
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
            return null;
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
            return null;
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
        if (!this.persistenceCoordinator.enqueueBaselineWrite(
                trackedProject.layout(),
                trackedProject.project().id().toString(),
                trackedProject.project().name(),
                chunkSnapshot,
                now
        )) {
            return null;
        }
        LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                "Queued missing baseline chunk {}:{} for project {} from mutation at {}",
                chunk.x(),
                chunk.z(),
                trackedProject.project().name(),
                pos
        );
        return chunkSnapshot;
    }

    private boolean ensureTrackedChunk(
            TrackedProject trackedProject, ServerLevel level, BlockPos pos,
            BlockState oldState, CompoundTag oldBlockEntity,
            io.github.luma.domain.model.WorldMutationSource source, boolean activeSessionRegion, Instant now
    ) throws IOException {
        return this.ensureTrackedChunk(
                trackedProject, level, pos, oldState, oldBlockEntity, null, null,
                source, activeSessionRegion, now, null
        );
    }

    private boolean ensureTrackedChunk(
            TrackedProject trackedProject, ServerLevel level, BlockPos pos,
            BlockState oldState, CompoundTag oldBlockEntity,
            EntityPayload oldEntityPayload, EntityPayload newEntityPayload,
            io.github.luma.domain.model.WorldMutationSource source, boolean activeSessionRegion, Instant now
    ) throws IOException {
        return this.ensureTrackedChunk(
                trackedProject, level, pos, oldState, oldBlockEntity, oldEntityPayload, newEntityPayload,
                source, activeSessionRegion, now, null
        );
    }

    private boolean ensureTrackedChunk(
            TrackedProject trackedProject, ServerLevel level, BlockPos pos,
            BlockState oldState, CompoundTag oldBlockEntity,
            EntityPayload oldEntityPayload, EntityPayload newEntityPayload,
            io.github.luma.domain.model.WorldMutationSource source, boolean activeSessionRegion,
            Instant now, ChunkSnapshotPayload[] capturedBaseline
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
        if (!ELIGIBILITY.allowsTrackedChunkExpansion(
                source,
                activeSessionRegion,
                this.hasActiveCausalAction(trackedProject.project().id().toString())
        )) {
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

        ChunkSnapshotPayload snapshot = this.captureChunkBaseline(
                trackedProject,
                level,
                pos,
                oldState,
                oldBlockEntity,
                oldEntityPayload,
                newEntityPayload,
                now
        );
        if (capturedBaseline != null) {
            capturedBaseline[0] = snapshot;
        }
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
            ChunkPoint chunk = ChunkPoint.from(pos);
            CaptureSessionState sessionState = this.workingDrafts.session(projectId);
            if (this.activeSessionRegionPolicy.contains(level, sessionState, chunk)) {
                return true;
            }
            if (ELIGIBILITY.isExplicitRootSource(source)) {
                return true;
            }
            if (this.hasActiveCausalAction(projectId)) {
                return true;
            }
            this.diagnosticsLogger.logSkippedCapture(
                    trackedProject,
                    source,
                    pos,
                    "outside-active-session-region",
                    "chunk " + chunk.x() + ":" + chunk.z()
                            + " is outside the active session region and has no explicit root source"
            );
            return false;
        }
        if (ELIGIBILITY.allowsSessionBootstrap(source, this.hasActiveCausalAction(projectId))) {
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
            boolean hiddenReconciliation
    ) throws IOException {
        String projectId = trackedProject.project().id().toString();
        TrackedChangeBuffer buffer = this.getOrCreateWorkingDraft(trackedProject, source, now);
        CaptureSessionState session = this.workingDrafts.session(projectId);
        if (session == null) {
            return;
        }
        this.liveBlockSectionReconciliationMarker.mark(
                trackedProject, level, source, pos, chunk, oldState, oldBlockEntity,
                hiddenReconciliation, ELIGIBILITY.isExplicitRootSource(source));
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
                buffer.size()
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
        boolean entitiesChanged = this.reconcileWorkingDraftEntitiesAgainstLiveWorld(level, session, now);
        if (entitiesChanged) {
            LumaMod.LOGGER.info(
                    "Reconciled working draft for project {} against live world; pending={}",
                    trackedProject.project().name(),
                    session.buffer().size()
            );
        }
        return entitiesChanged;
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

    private boolean hiddenReconciliation(
            CaptureSessionState session,
            ChunkPoint chunk,
            io.github.luma.domain.model.WorldMutationSource source
    ) {
        return ELIGIBILITY.hiddenInBuilderSurfaces(source)
                || session != null && session.isHiddenReconciliationChunk(chunk);
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

    static boolean shouldTrackPersistentMutation(io.github.luma.domain.model.WorldMutationSource source) {
        return !WorldMutationContext.captureSuppressed()
                && source != null
                && source != io.github.luma.domain.model.WorldMutationSource.RESTORE;
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
