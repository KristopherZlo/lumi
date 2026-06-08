package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.RestoreReturnPoint;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.minecraft.capture.DeferredActionFalloutGuard;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.minecraft.world.MechanismReplayScope;
import io.github.luma.minecraft.world.PreparedBlockPlacement;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.PreparedChunkBatchCollapser;
import io.github.luma.minecraft.world.PreparedWorldChangeBatches;
import io.github.luma.minecraft.world.WorldChangeBatchPreparer;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.SnapshotReader;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

/**
 * Starts one-step rollback workflows for fast redstone iteration.
 */
public final class QuickRollbackService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final RestoreService restoreService = new RestoreService();
    private final UndoRedoHistoryManager undoRedoHistoryManager = UndoRedoHistoryManager.getInstance();
    private final HistoryCaptureManager captureManager = HistoryCaptureManager.getInstance();
    private final DeferredActionFalloutGuard deferredActionFalloutGuard = DeferredActionFalloutGuard.getInstance();
    private final RestoreMechanismReconciliationPlanner mechanismReconciliationPlanner =
            new RestoreMechanismReconciliationPlanner();
    private final WorldChangeBatchPreparer batchPreparer = new WorldChangeBatchPreparer();
    private final BlockTargetStateResolver blockTargetStateResolver = new BlockTargetStateResolver();
    private final PreparedChunkBatchCollapser batchCollapser = new PreparedChunkBatchCollapser();
    private final RestoreEntityStateResolver entityStateResolver = new RestoreEntityStateResolver(
            new RestoreChunkCollector(new PatchMetaRepository()),
            new BaselineChunkRepository(),
            new SnapshotReader(),
            new RestorePayloadLoader(),
            new RestorePlanBuilder(),
            this.batchCollapser
    );
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();

    public OperationHandle quickRollback(ServerLevel level, String projectName) throws IOException {
        return this.quickRollback(level, projectName, null);
    }

    public OperationHandle quickRollback(ServerLevel level, String projectName, Bounds3i selectedBounds) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        this.requireIdle(level);
        ProjectVariant activeVariant = this.activeVariant(layout, project.activeVariantId(), projectName);
        if (activeVariant.headVersionId() == null || activeVariant.headVersionId().isBlank()) {
            throw new IllegalArgumentException("Current branch has no committed head yet");
        }

        Optional<RecoveryDraft> persistedDraft = this.recoveryRepository.loadDraft(layout)
                .filter(draft -> !draft.isEmpty());
        Optional<TrackedChangeBuffer> frozenSession = this.captureManager
                .freezeWorkingDraftForRecovery(level.getServer(), project.id().toString());
        Optional<RecoveryDraft> frozenDraft = frozenSession
                .map(TrackedChangeBuffer::toDraft)
                .filter(draft -> !draft.isEmpty());
        RecoveryDraft pendingDraft = frozenDraft
                .or(() -> persistedDraft)
                .orElseThrow(() -> new IllegalArgumentException("No pending tracked changes for " + projectName));
        this.requireCurrentHeadDraft(pendingDraft, activeVariant, projectName);

        QuickRollbackDraftPlan plan = QuickRollbackDraftPlan.fromDraft(activeVariant.headVersionId(), pendingDraft, selectedBounds);
        if (plan.isEmpty()) {
            throw new IllegalArgumentException("No pending tracked changes for " + projectName);
        }

        this.deferredActionFalloutGuard.suppressAction(plan.actionId(), level.getGameTime());
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                Instant.now(),
                "quick-rollback-started",
                "Started quick rollback to active branch head " + activeVariant.headVersionId(),
                activeVariant.headVersionId(),
                activeVariant.id()
        ));
        LumaMod.LOGGER.info(
                "Starting quick rollback for project {} to active head {} with {} changes",
                project.name(),
                activeVariant.headVersionId(),
                plan.totalChangeCount()
        );
        LumaDebugLog.log(
                project,
                "restore",
                "Starting quick rollback for project {} from {} with {} pending changes",
                project.name(),
                frozenDraft.isPresent() ? "frozen live buffer" : "persisted draft",
                plan.totalChangeCount()
        );

        return this.worldOperationManager.startPreparedApplyOperation(
                level,
                project.id().toString(),
                "quick-rollback",
                "blocks",
                LumaDebugLog.enabled(project),
                progressSink -> {
                    progressSink.update(
                            OperationStage.PREPARING,
                            0,
                            plan.totalChangeCount(),
                            "Preparing quick rollback"
                    );
                    PreparedWorldChangeBatches analyzed = this.batchPreparer.prepareUndoRedoAnalyzed(
                            level,
                            plan.blockChanges(),
                            plan.entityChanges(),
                            true,
                            (completed, total) -> progressSink.update(
                                    OperationStage.PREPARING,
                                    completed,
                                    total,
                                    "Decoded quick rollback"
                            )
                    );
                    List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
                    ProjectVersion activeHead = versions.stream()
                            .filter(version -> version.id().equals(activeVariant.headVersionId()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Active head version is missing: " + activeVariant.headVersionId()));
                    List<PreparedChunkBatch> batches = this.withMechanismReconciliation(
                            level,
                            layout,
                            project,
                            versions,
                            activeHead,
                            analyzed.batches(),
                            analyzed.mechanismReplayScope(),
                            selectedBounds
                    );
                    if (selectedBounds == null) {
                        batches = this.entityStateResolver.withAuthoritativeEntityReplacementBatches(
                                layout,
                                versions,
                                activeVariant.headVersionId(),
                                batches
                        );
                    }
                    List<PreparedChunkBatch> finalBatches = batches;
                    return new WorldOperationManager.PreparedApplyOperation(
                            finalBatches,
                            () -> this.completeQuickRollback(level, layout, project, activeVariant, plan, finalBatches.size())
                    );
                }
        );
    }

    public OperationHandle returnBeforeLastRestore(ServerLevel level, String projectName) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        this.requireIdle(level);
        RestoreReturnPoint point = this.recoveryRepository.loadRestoreReturnPoint(layout)
                .orElseThrow(() -> new IllegalArgumentException("No restore return point is available"));
        this.activeVariant(layout, point.variantId(), projectName);
        return this.restoreService.restoreToVariant(level, projectName, point.versionId(), point.variantId());
    }

    private void requireIdle(ServerLevel level) {
        if (this.worldOperationManager.hasActiveOperation(level.getServer())) {
            throw new IllegalStateException("Another world operation is already running");
        }
    }

    private void requireCurrentHeadDraft(RecoveryDraft draft, ProjectVariant activeVariant, String projectName) {
        if (!Objects.equals(draft.variantId(), activeVariant.id())
                || !Objects.equals(draft.baseVersionId(), activeVariant.headVersionId())) {
            throw new IllegalArgumentException("Pending tracked changes are not based on the current branch head for " + projectName);
        }
    }

    private void completeQuickRollback(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            ProjectVariant activeVariant,
            QuickRollbackDraftPlan plan,
            int batchCount
    ) throws IOException {
        Instant now = Instant.now();
        this.undoRedoHistoryManager.recordAction(
                project.id().toString(),
                level.dimension().identifier().toString(),
                plan.actionId(),
                plan.actor(),
                plan.blockChanges(),
                plan.entityChanges(),
                now
        );
        this.captureManager.discardSession(level.getServer(), project.id().toString());
        this.saveRemainingDraft(layout, plan.remainingDraft());
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "quick-rollback-completed",
                "Completed quick rollback to active branch head " + activeVariant.headVersionId(),
                activeVariant.headVersionId(),
                activeVariant.id()
        ));
        this.captureManager.invalidateProjectCache(level.getServer());
        LumaMod.LOGGER.info(
                "Completed quick rollback for project {} to active head {} with {} prepared chunk batches",
                project.name(),
                activeVariant.headVersionId(),
                batchCount
        );
    }

    private void saveRemainingDraft(ProjectLayout layout, RecoveryDraft remainingDraft) throws IOException {
        if (remainingDraft != null && !remainingDraft.isEmpty()) {
            this.recoveryRepository.saveDraft(layout, remainingDraft);
        }
    }

    private List<PreparedChunkBatch> withMechanismReconciliation(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion,
            List<PreparedChunkBatch> batches,
            MechanismReplayScope mechanismScope,
            Bounds3i selectedBounds
    ) throws IOException {
        List<BlockPoint> positions = this.mechanismReconciliationPositions(
                project,
                mechanismScope,
                selectedBounds,
                level
        );
        if (positions.isEmpty()) {
            return batches == null ? List.of() : batches;
        }
        Map<BlockPoint, io.github.luma.domain.model.StatePayload> targetStates = this.blockTargetStateResolver.resolve(
                layout,
                project,
                versions,
                targetVersion,
                positions
        );
        if (targetStates.isEmpty()) {
            return batches == null ? List.of() : batches;
        }
        List<PreparedChunkBatch> combined = new ArrayList<>(batches == null ? List.of() : batches);
        combined.addAll(this.batchPreparer.prepareTargetStates(
                level,
                targetStates,
                PreparedBlockPlacement.ReplayHint.FORCE_FINAL_REPLAY_AND_SUPPRESS_POST_REPLAY_MECHANISM
        ));
        return this.batchCollapser.collapse(combined);
    }

    List<BlockPoint> mechanismReconciliationPositions(
            BuildProject project,
            MechanismReplayScope mechanismScope,
            Bounds3i selectedBounds,
            ServerLevel level
    ) {
        if (mechanismScope == null || mechanismScope.isEmpty()) {
            return List.of();
        }
        Optional<List<BlockPoint>> positions = this.mechanismReconciliationPlanner.boundedMechanismReplayPositions(
                project,
                mechanismScope,
                level
        );
        if (positions.isEmpty()) {
            LumaMod.LOGGER.info(
                    "Skipped quick rollback mechanism target-state reconciliation because scope exceeded {} cells",
                    RestoreMechanismReconciliationPlanner.MAX_MECHANISM_RECONCILIATION_CELLS
            );
            return List.of();
        }
        return positions.orElseThrow().stream()
                .filter(position -> selectedBounds == null || selectedBounds.contains(position))
                .toList();
    }

    private ProjectVariant activeVariant(ProjectLayout layout, String variantId, String projectName) throws IOException {
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        return variants.stream()
                .filter(variant -> variant.id().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active branch is missing for " + projectName));
    }
}
