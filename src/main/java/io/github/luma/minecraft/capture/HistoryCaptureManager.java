package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BlockPoint;
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
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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

    private static final HistoryCaptureManager INSTANCE = new HistoryCaptureManager();
    private static final Duration ACTIVE_DRAFT_FLUSH_INTERVAL = Duration.ofSeconds(3);
    private static final int IDLE_FLUSH_TICK_INTERVAL = 5;
    private static final int STARTUP_CAPTURE_TRACE_LIMIT = 32;
    private static final int CAPTURE_SUMMARY_ENTRY_LIMIT = 4;
    private static final WorldMutationCapturePolicy CAPTURE_POLICY = new WorldMutationCapturePolicy();
    private static final EntityMutationCapturePolicy ENTITY_CAPTURE_POLICY = new EntityMutationCapturePolicy();
    private static final MutationSourcePolicy SOURCE_POLICY = new MutationSourcePolicy();

    private final HistoryDebugLog historyDebugLog = new HistoryDebugLog();
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
    private final ChunkSnapshotCaptureService chunkSnapshotCaptureService = new ChunkSnapshotCaptureService();
    private final ServerThreadExecutor serverThreadExecutor = new ServerThreadExecutor();
    private final ActiveSessionRegionPolicy activeSessionRegionPolicy = new ActiveSessionRegionPolicy();
    private final PersistentBlockStatePolicy persistentBlockStatePolicy = new PersistentBlockStatePolicy();
    private long idleFlushTicker;

    private HistoryCaptureManager() {
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
        boolean explicitRootSource = this.isExplicitRootSource(source);
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
                        || !SOURCE_POLICY.canCaptureDeferredPreMutationBaseline(
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

                this.captureSessionChunkBaseline(trackedProject, level, session, chunk, pos, oldState, oldBlockEntity);
                if (!explicitRootSource) {
                    this.recordBaselineCorrection(session, pos, oldState, oldBlockEntity);
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
            WorldMutationCapturePolicy.CaptureResult captureResult = CAPTURE_POLICY.evaluate(
                    source,
                    pos,
                    oldState,
                    newState,
                    oldBlockEntity,
                    newBlockEntity
            );
            if (captureResult.decision() == WorldMutationCapturePolicy.CaptureDecision.REJECTED) {
                LumaDebugLog.log(
                        "capture",
                        "Skipped {} mutation at {} in {} because it is unsupported, unchanged, or transient: {} -> {}",
                        source,
                        pos,
                        level.dimension().identifier(),
                        oldState,
                        newState
                );
                return;
            }
            List<TrackedProject> matchingProjects = this.matchingProjects(level, pos);
            if (matchingProjects.isEmpty()) {
                if (captureResult.decision() != WorldMutationCapturePolicy.CaptureDecision.CAPTURED
                        || !allowsAutomaticProjectCreation(source)) {
                    LumaDebugLog.log(
                            "capture",
                            "Skipped {} mutation at {} in {} because no tracked workspace exists and the source cannot bootstrap one",
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
                CaptureSessionState.DeferredActionContext deferredActionContext =
                        this.deferredActionContext(existingSession, chunk, source);
                if (captureResult.decision() == WorldMutationCapturePolicy.CaptureDecision.DEFER_TO_STABILIZATION
                        && !this.canUseDeferredStabilization(source, deferredActionContext)) {
                    LumaDebugLog.log(
                            trackedProject.project(),
                            "capture",
                            "Skipped deferred {} mutation at {} for project {} because no causal action is active",
                            source,
                            pos,
                            trackedProject.project().name()
                    );
                    this.historyDebugLog.logSkippedDeferredBlock(
                            trackedProject.project(),
                            source,
                            pos,
                            oldState,
                            newState,
                            "missing-causal-action"
                    );
                    continue;
                }
                WorldMutationCapturePolicy.CapturedMutation mutation = captureResult.mutation();
                StoredBlockChange capturedChange = mutation == null ? null : mutation.change();
                LumaDebugLog.log(
                        trackedProject.project(),
                        "capture",
                        "Recording {} mutation for project {} at {} in {}: {} -> {}",
                        source,
                        trackedProject.project().name(),
                        pos,
                        level.dimension().identifier(),
                        oldState,
                        newState
                );
                if (!this.canCaptureIntoSession(trackedProject, level, source, pos)) {
                    continue;
                }
                boolean activeSessionRegion = this.activeSessionRegionPolicy.contains(level, existingSession, chunk);
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
                    session.recordBaselineCorrection(
                            BlockPoint.from(pos),
                            StatePayload.capture(mutation.oldState(), mutation.oldBlockEntity())
                    );
                }
                if (this.isExplicitRootSource(source)) {
                    this.captureSessionChunkBaseline(trackedProject, level, session, chunk, pos, mutation.oldState(), mutation.oldBlockEntity());
                    session.addRootChunk(chunk);
                } else if (this.usesDeferredStabilization(trackedProject.project(), source)) {
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
                    this.captureSessionChunkBaseline(trackedProject, level, session, chunk, pos, mutation.oldState(), mutation.oldBlockEntity());
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
                int pendingBefore = buffer.size();
                buffer.addChange(capturedChange, now);
                this.liveUndoRedoActionRecorder.recordBlockAction(trackedProject, level, capturedChange, now);
                int pendingAfter = buffer.size();
                this.historyDebugLog.logCapturedBlock(
                        trackedProject.project(),
                        "direct",
                        source,
                        pos,
                        mutation.oldState(),
                        mutation.newState(),
                        pendingBefore,
                        pendingAfter
                );
                CaptureSessionDiagnostics diagnostics = this.diagnosticsForSession(projectId);
                diagnostics.record(
                        source,
                        pos,
                        mutation.oldState(),
                        mutation.newState(),
                        mutation.oldBlockEntity() != null,
                        mutation.newBlockEntity() != null
                );
                this.logAcceptedCaptureTrace(trackedProject.project(), buffer, diagnostics, pendingBefore, pendingAfter);
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
                this.logBufferProgress(trackedProject.project(), buffer, diagnostics);
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
                this.diagnosticsForSession(projectId).addActiveChunk(new ChunkPoint(pos.getX() >> 4, pos.getZ() >> 4));
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
                this.logBufferProgress(trackedProject.project(), buffer, this.diagnosticsForSession(projectId));
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
        this.freezeWorkingDraft(server, projectId);
    }

    public Optional<RecoveryDraft> snapshotDraft(MinecraftServer server, String projectId) throws IOException {
        return this.serverThreadExecutor.call(server, () -> this.snapshotDraftOnServerThread(server, projectId));
    }

    public boolean hasInterruptedDraft(MinecraftServer server, String projectId) throws IOException {
        return this.serverThreadExecutor.call(server, () -> this.hasInterruptedDraftOnServerThread(server, projectId));
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

    public Optional<TrackedChangeBuffer> freezeSession(MinecraftServer server, String projectId) throws IOException {
        return this.freezeWorkingDraft(server, projectId);
    }

    private Optional<TrackedChangeBuffer> freezeWorkingDraftOnServerThread(MinecraftServer server, String projectId) throws IOException {
        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        CaptureSessionState sessionState = this.workingDrafts.session(projectId);
        if (trackedProject != null && sessionState != null) {
            this.reconcileSession(server, trackedProject, sessionState, true);
        }
        return this.workingDrafts.freezeAfterReconciliation(projectId, trackedProject);
    }

    private Optional<TrackedChangeBuffer> freezeWorkingDraftForShutdownOnServerThread(
            MinecraftServer server,
            String projectId
    ) throws IOException {
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

    public Optional<TrackedChangeBuffer> consumeSession(MinecraftServer server, String projectId) throws IOException {
        return this.consumeWorkingDraft(server, projectId);
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
        this.serverThreadExecutor.run(server, () -> this.discardSessionOnServerThread(server, projectId));
    }

    private void discardSessionOnServerThread(MinecraftServer server, String projectId) throws IOException {
        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        this.workingDrafts.discard(projectId, trackedProject);
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

        for (Map.Entry<String, TrackedChangeBuffer> entry : this.workingDrafts.activeBufferEntries()) {
            String projectId = entry.getKey();
            TrackedChangeBuffer session = entry.getValue();
            int idleSeconds = idleThresholds.getOrDefault(projectId, 5);
            if (Duration.between(session.updatedAt(), now).getSeconds() >= idleSeconds) {
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

            if (!this.workingDrafts.isDirty(projectId)) {
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
                this.finalizeProjectSession(server, projectId);
            } catch (IOException exception) {
                LumaMod.LOGGER.warn("Failed to finalize idle session for {}", projectId, exception);
            }
        }
    }

    public void flushAll(MinecraftServer server) {
        for (String projectId : this.workingDrafts.activeProjectIds()) {
            try {
                this.serverThreadExecutor.call(
                        server,
                        () -> this.freezeWorkingDraftForShutdownOnServerThread(server, projectId)
                );
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
        return SOURCE_POLICY.canUse(dedicatedServer, accessAllowed, source);
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
        if (!SOURCE_POLICY.allowsTrackedChunkExpansion(source, activeSessionRegion)) {
            LumaDebugLog.log(
                    trackedProject.project(),
                    "capture",
                    "Skipped {} mutation at {} because chunk {}:{} is not tracked yet and the source cannot expand tracking",
                    source,
                    pos,
                    chunk.x(),
                    chunk.z()
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
            if (!SOURCE_POLICY.canUseDirectCapture(source, WorldMutationContext.currentActionId())) {
                LumaDebugLog.log(
                        trackedProject.project(),
                        "capture",
                        "Skipped {} mutation at {} for project {} because no causal action is active",
                        source,
                        pos,
                        trackedProject.project().name()
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
            LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                    "Skipped {} mutation at {} for project {} because chunk {}:{} is outside the active session region",
                    source,
                    pos,
                    trackedProject.project().name(),
                    chunk.x(),
                    chunk.z()
            );
            return false;
        }
        if (allowsSessionBootstrap(source)) {
            return true;
        }
        LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                "Skipped {} mutation at {} for project {} because no active session exists and the source cannot bootstrap capture",
                source,
                pos,
                trackedProject.project().name()
        );
        return false;
    }

    private CaptureSessionDiagnostics diagnosticsForSession(String projectId) {
        return this.workingDrafts.diagnosticsForSession(projectId);
    }

    private void clearSessionDiagnostics(String projectId) {
        this.workingDrafts.clearSessionDiagnostics(projectId);
    }

    private static boolean isExplicitRootSource(io.github.luma.domain.model.WorldMutationSource source) {
        return SOURCE_POLICY.isExplicitRootSource(source);
    }

    private boolean usesDeferredStabilization(BuildProject project, io.github.luma.domain.model.WorldMutationSource source) {
        return SOURCE_POLICY.usesDeferredStabilization(project, source);
    }

    private boolean canUseDeferredStabilization(
            io.github.luma.domain.model.WorldMutationSource source,
            CaptureSessionState.DeferredActionContext deferredActionContext
    ) {
        return SOURCE_POLICY.canUseDeferredStabilization(source, actionId(deferredActionContext));
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
        if (this.isExplicitRootSource(source)) {
            session.addRootChunk(chunk);
        }
        this.recordBaselineCorrection(session, pos, oldState, oldBlockEntity);
        this.captureSessionChunkBaseline(trackedProject, level, session, chunk, pos, oldState, oldBlockEntity);
        session.markDirtySection(
                new ChunkSectionPoint(chunk, Math.floorDiv(pos.getY(), 16)),
                deferredActionContext,
                level.getGameTime()
        );
        this.workingDrafts.markDirty(projectId);
        CaptureSessionDiagnostics diagnostics = this.diagnosticsForSession(projectId);
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

    private void captureSessionChunkBaseline(
            TrackedProject trackedProject,
            ServerLevel level,
            CaptureSessionState session,
            ChunkPoint chunk,
            BlockPos changedPos,
            BlockState oldState,
            CompoundTag oldBlockEntity
    ) {
        if (session.hasBaselineChunk(chunk)) {
            return;
        }
        session.captureBaselineChunk(
                chunk,
                this.stabilizationService.captureBaselineChunkState(
                        level,
                        trackedProject.project(),
                        chunk,
                        changedPos,
                        oldState,
                        oldBlockEntity
                )
        );
    }

    private void recordBaselineCorrection(
            CaptureSessionState session,
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity
    ) {
        if (session == null || pos == null) {
            return;
        }
        PersistentBlockStatePolicy.PersistentBlockState persistentState =
                this.persistentBlockStatePolicy.normalize(oldState, oldBlockEntity);
        session.recordBaselineCorrection(
                BlockPoint.from(pos),
                StatePayload.capture(persistentState.state(), persistentState.blockEntityTag())
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

        if (result.inFlight() || result.chunkCount() <= 0) {
            return;
        }
        String projectId = trackedProject.project().id().toString();
        this.logReconciliation(trackedProject, result);
        this.liveUndoRedoActionRecorder.recordReconciledChanges(trackedProject, level, result, Instant.now());
        if (session.buffer().isEmpty()) {
            this.workingDrafts.discardIfEmpty(trackedProject, "after reconciliation");
        }
    }

    private void logReconciliation(
            TrackedProject trackedProject,
            SessionStabilizationService.ReconciliationResult result
    ) {
        String message = "Reconciled {} dirty chunks for project {}: delta={} composed={} buffer {} -> {}";
        LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                message,
                result.chunkCount(),
                trackedProject.project().name(),
                result.deltaChangeCount(),
                result.composedChangeCount(),
                result.bufferBefore(),
                result.bufferAfter()
        );
        if (result.bufferChanged()) {
            LumaMod.LOGGER.info(
                    message,
                    result.chunkCount(),
                    trackedProject.project().name(),
                    result.deltaChangeCount(),
                    result.composedChangeCount(),
                    result.bufferBefore(),
                    result.bufferAfter()
            );
        }
    }

    private CaptureSessionState.DeferredActionContext deferredActionContext(
            CaptureSessionState session,
            ChunkPoint chunk,
            io.github.luma.domain.model.WorldMutationSource source
    ) {
        CaptureSessionState.DeferredActionContext currentContext = this.currentDeferredActionContext();
        if (currentContext != null) {
            return currentContext;
        }
        if (!canReusePendingMechanismAction(source) || session == null) {
            return null;
        }
        return session.deferredActionContext(chunk);
    }

    private CaptureSessionState.DeferredActionContext currentDeferredActionContext() {
        String actionId = WorldMutationContext.currentActionId();
        if (actionId == null || actionId.isBlank()) {
            return null;
        }
        return new CaptureSessionState.DeferredActionContext(
                actionId,
                WorldMutationContext.currentActor(),
                WorldMutationContext.currentAccessAllowed()
        );
    }

    private static boolean canReusePendingMechanismAction(io.github.luma.domain.model.WorldMutationSource source) {
        return source == io.github.luma.domain.model.WorldMutationSource.BLOCK_UPDATE
                || source == io.github.luma.domain.model.WorldMutationSource.PISTON;
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

    private void logAcceptedCaptureTrace(
            BuildProject project,
            TrackedChangeBuffer buffer,
            CaptureSessionDiagnostics diagnostics,
            int pendingBefore,
            int pendingAfter
    ) {
        int accepted = diagnostics.acceptedMutations();
        if (accepted <= STARTUP_CAPTURE_TRACE_LIMIT) {
            LumaDebugLog.log(
                    project,
                    "capture",
                    "Capture trace {}/{} for project {}: source={} sessionSource={} pos={} chunk={}:{} {} -> {} oldBe={} newBe={} pending={} delta={}",
                    accepted,
                    STARTUP_CAPTURE_TRACE_LIMIT,
                    project.name(),
                    diagnostics.lastSource(),
                    buffer.mutationSource(),
                    this.formatPos(diagnostics.lastPos()),
                    diagnostics.lastChunk().x(),
                    diagnostics.lastChunk().z(),
                    diagnostics.lastOldBlockId(),
                    diagnostics.lastNewBlockId(),
                    diagnostics.lastOldBlockEntity(),
                    diagnostics.lastNewBlockEntity(),
                    pendingAfter,
                    pendingAfter - pendingBefore
            );
            if (accepted == STARTUP_CAPTURE_TRACE_LIMIT) {
                LumaDebugLog.log(
                        project,
                        "capture",
                        "Capture trace limit reached for project {}. Further accepted mutations in this session will be summarized only at progress checkpoints.",
                        project.name()
                );
            }
        }
    }

    private void logBufferProgress(BuildProject project, TrackedChangeBuffer buffer, CaptureSessionDiagnostics diagnostics) {
        int size = buffer.size();
        if (size == 1 || size == 64 || size == 256 || (size % 1024) == 0) {
            LumaMod.LOGGER.info(
                    "Captured {} pending changes for project {} (accepted={} sources=[{}] transitions=[{}] last={} source={} chunk={}:{})",
                    size,
                    project.name(),
                    diagnostics.acceptedMutations(),
                    diagnostics.describeTopSources(CAPTURE_SUMMARY_ENTRY_LIMIT),
                    diagnostics.describeTopTransitions(CAPTURE_SUMMARY_ENTRY_LIMIT),
                    this.formatPos(diagnostics.lastPos()),
                    diagnostics.lastSource(),
                    diagnostics.lastChunk().x(),
                    diagnostics.lastChunk().z()
            );
        }
    }

    private String formatPos(BlockPos pos) {
        if (pos == null) {
            return "unknown";
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public static boolean shouldCaptureMutation(io.github.luma.domain.model.WorldMutationSource source) {
        if (WorldMutationContext.captureSuppressed()) {
            return false;
        }
        return CAPTURE_POLICY.shouldCaptureMutation(source);
    }

    public static boolean allowsAutomaticProjectCreation(io.github.luma.domain.model.WorldMutationSource source) {
        return SOURCE_POLICY.allowsAutomaticProjectCreation(source);
    }

    public static boolean allowsSessionBootstrap(io.github.luma.domain.model.WorldMutationSource source) {
        return SOURCE_POLICY.allowsSessionBootstrap(source);
    }

    public static boolean allowsTrackedChunkExpansion(io.github.luma.domain.model.WorldMutationSource source) {
        return SOURCE_POLICY.allowsTrackedChunkExpansion(source);
    }

    static boolean requiresActiveRegionMembership(io.github.luma.domain.model.WorldMutationSource source) {
        return SOURCE_POLICY.requiresActiveRegionMembership(source);
    }

    static boolean isWithinChunkRadius(ChunkPoint first, ChunkPoint second, int radius) {
        if (first == null || second == null || radius < 0) {
            return false;
        }
        return Math.abs(first.x() - second.x()) <= radius
                && Math.abs(first.z() - second.z()) <= radius;
    }

    public static String defaultActor(io.github.luma.domain.model.WorldMutationSource source) {
        return SOURCE_POLICY.defaultActor(source);
    }
}
