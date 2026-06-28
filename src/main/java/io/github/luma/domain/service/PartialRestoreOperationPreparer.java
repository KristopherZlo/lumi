package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PartialRestorePlanSummary;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RestoreEntityTypeSelection;
import io.github.luma.domain.model.RestorePlanMode;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.debug.PartialRestoreDiagnosticsLog;
import io.github.luma.minecraft.world.EntityApplyMode;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.PreparedChunkBatchCollapser;
import io.github.luma.minecraft.world.PreparedWorldChangeBatches;
import io.github.luma.minecraft.world.WorldChangeBatchPreparer;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;

/**
 * Prepares selected-area restore drafts and apply batches.
 */
final class PartialRestoreOperationPreparer {

    private final VersionRepository versionRepository = new VersionRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final RestoreRequestResolver requestResolver = new RestoreRequestResolver();
    private final PartialRestorePendingDraftProvider pendingDraftProvider = new PartialRestorePendingDraftProvider();
    private final DirectRestorePatchPlanner directRestorePatchPlanner = new DirectRestorePatchPlanner();
    private final RestoreChunkCollector chunkCollector = new RestoreChunkCollector(new PatchMetaRepository());
    private final RestorePayloadLoader payloadLoader = new RestorePayloadLoader();
    private final RestoreMechanismReconciliationPlanner mechanismReconciliationPlanner =
            new RestoreMechanismReconciliationPlanner();
    private final PartialRestorePlanner partialRestorePlanner = new PartialRestorePlanner();
    private final PartialRestoreEntityPlanner partialRestoreEntityPlanner = new PartialRestoreEntityPlanner();
    private final PartialRestoreTargetStatePlanner targetStatePlanner = new PartialRestoreTargetStatePlanner();
    private final WorldChangeBatchPreparer batchPreparer = new WorldChangeBatchPreparer();
    private final PreparedChunkBatchCollapser batchCollapser = new PreparedChunkBatchCollapser();
    private final RestoreCompletionCoordinator completionCoordinator = new RestoreCompletionCoordinator();
    private final PartialRestoreDiagnosticsLog diagnosticsLog;

    PartialRestoreOperationPreparer(PartialRestoreDiagnosticsLog diagnosticsLog) {
        this.diagnosticsLog = diagnosticsLog == null ? new PartialRestoreDiagnosticsLog() : diagnosticsLog;
    }

    WorldOperationManager.PreparedApplyOperation prepare(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            PartialRestoreRequest request,
            Predicate<BlockPoint> hardScope,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        progressSink.update(OperationStage.PREPARING, 0, 0, "Preparing partial restore request");
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVersion targetVersion = this.requestResolver.resolveVersion(project, versions, variants, request.targetVersionId());
        ProjectVariant activeVariant = this.requestResolver.activeVariant(project, variants);
        RecoveryDraft pendingDraft = this.pendingDraftProvider.freeze(level, layout, project.id().toString())
                .orElse(null);

        LumaMod.LOGGER.info(
                "Starting partial restore for project {} to version {} over {}",
                project.name(),
                targetVersion.id(),
                request.bounds()
        );
        this.recoveryRepository.appendJournalEntry(layout, new io.github.luma.domain.model.RecoveryJournalEntry(
                Instant.now(),
                "partial-restore-started",
                "Started partial restore to version " + targetVersion.id(),
                targetVersion.id(),
                activeVariant.id()
        ));

        PartialRestoreDraft partialDraft = this.applyEntityTypeSelection(this.buildDraft(
                layout,
                project,
                versions,
                variants,
                activeVariant,
                targetVersion,
                pendingDraft,
                request,
                hardScope,
                level.getMinY(),
                level.getMaxY(),
                progressSink
        ), request.entityTypeSelection());
        this.diagnosticsLog.logPlannedDraft(
                project,
                activeVariant,
                targetVersion,
                request,
                partialDraft.mode(),
                partialDraft.draft()
        );
        if (partialDraft.draft().isEmpty()) {
            throw new IllegalArgumentException(request.restoreMode() == PartialRestoreMode.OUTSIDE_SELECTED_AREA
                    ? "Partial restore has no changes outside the selected region"
                    : "Partial restore has no changes inside the selected region");
        }
        List<PreparedChunkBatch> decodedBatches = this.decodeStoredChanges(
                level,
                partialDraft.draft().changes(),
                partialDraft.draft().entityChanges(),
                true
        );
        List<PreparedChunkBatch> batches;
        try (var ignored = LumaLoadLog.measure(
                "restore",
                "PreparedChunkBatchCollapser.collapse",
                "source=partial-restore, batches=" + decodedBatches.size()
        )) {
            batches = this.batchCollapser.collapse(decodedBatches);
        }
        boolean diagnosticsEnabled = this.diagnosticsLog.enabled(request);
        return new WorldOperationManager.PreparedApplyOperation(
                batches,
                () -> {
                    if (diagnosticsEnabled) {
                        this.diagnosticsLog.logPostApplyRemaining(
                                level,
                                project,
                                request,
                                partialDraft.mode(),
                                partialDraft.draft()
                        );
                    }
                    this.completionCoordinator.completePartialRestore(
                            level,
                            layout,
                            project,
                            pendingDraft,
                            request,
                            partialDraft.draft(),
                            batches.size()
                    );
                },
                diagnosticsEnabled
        );
    }

    PartialRestorePlanSummary summarize(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            PartialRestoreRequest request,
            Predicate<BlockPoint> hardScope
    ) throws IOException {
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVersion targetVersion = this.requestResolver.resolveVersion(project, versions, variants, request.targetVersionId());
        ProjectVariant activeVariant = this.requestResolver.activeVariant(project, variants);
        Optional<RecoveryDraft> pendingDraft = this.pendingDraftProvider.snapshot(level, layout, project.id().toString());
        PartialRestoreDraft draft = this.buildDraft(
                layout,
                project,
                versions,
                variants,
                activeVariant,
                targetVersion,
                pendingDraft.orElse(null),
                request,
                hardScope,
                level.getMinY(),
                level.getMaxY(),
                (stage, completed, total, detail) -> {
                }
        );

        return new PartialRestorePlanSummary(
                draft.draft().isEmpty() ? RestorePlanMode.NO_OP : draft.mode(),
                request.bounds(),
                request.restoreMode(),
                request.regionSource(),
                ChunkSelectionFactory.fromStoredChanges(draft.draft().changes()),
                activeVariant.id(),
                activeVariant.headVersionId(),
                targetVersion.id(),
                draft.draft().changes().size(),
                draft.draft().entityChanges().size()
        );
    }

    PartialRestoreDraft buildDraft(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVariant activeVariant,
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft,
            PartialRestoreRequest request,
            Predicate<BlockPoint> hardScope,
            int worldMinY,
            int worldMaxY,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        DirectRestorePatchPlan directPlan = this.directRestorePatchPlanner.applicablePlan(project, versions, variants, targetVersion);
        if (directPlan == null) {
            return this.buildTargetStateDraft(
                    layout,
                    project,
                    versions,
                    activeVariant,
                    targetVersion,
                    pendingDraft,
                    request,
                    hardScope,
                    worldMinY,
                    worldMaxY,
                    progressSink
            );
        }

        List<ChunkPoint> selectedChunks = request.restoreMode() == PartialRestoreMode.OUTSIDE_SELECTED_AREA
                ? null
                : this.chunkCollector.chunksIntersecting(request.bounds());
        PatchWorldChanges reverseChanges = this.payloadLoader.loadVersionWorldChanges(
                layout,
                directPlan.reverseVersions(),
                selectedChunks
        );
        PatchWorldChanges forwardChanges = this.payloadLoader.loadVersionWorldChanges(
                layout,
                directPlan.forwardVersions(),
                selectedChunks
        );
        if (this.mechanismReconciliationPlanner.containsMechanismState(pendingDraft == null ? List.of() : pendingDraft.changes())
                || this.mechanismReconciliationPlanner.containsMechanismState(reverseChanges.blockChanges())
                || this.mechanismReconciliationPlanner.containsMechanismState(forwardChanges.blockChanges())) {
            LumaDebugLog.log(
                    project,
                    "restore",
                    "Partial restore for project {} target {} switched to target-state planning because direct path contains mechanism state",
                    project.name(),
                    targetVersion.id()
            );
            try {
                return this.buildTargetStateDraft(
                        layout,
                        project,
                        versions,
                        activeVariant,
                        targetVersion,
                        pendingDraft,
                        request,
                        hardScope,
                        worldMinY,
                        worldMaxY,
                        progressSink
                );
            } catch (PartialRestoreTargetStateUnavailableException exception) {
                LumaMod.LOGGER.info(
                        "Partial restore for project {} to {} could not safely build target-state plan ({}); aborting instead of bounded patch replay",
                        project.name(),
                        targetVersion.id(),
                        exception.getMessage()
                );
                LumaDebugLog.log(
                        project,
                        "restore",
                        "Partial restore for project {} target {} aborted instead of bounded patch replay after target-state planning was unavailable: {}",
                        project.name(),
                        targetVersion.id(),
                        exception.getMessage()
                );
                throw exception;
            }
        }
        int lineageChangeCount = reverseChanges.blockChanges().size()
                + reverseChanges.entityChanges().size()
                + forwardChanges.blockChanges().size()
                + forwardChanges.entityChanges().size();
        progressSink.update(OperationStage.PREPARING, 0, Math.max(1, lineageChangeCount), "Filtering partial restore region");
        List<StoredBlockChange> partialChanges = this.partialRestorePlanner.plan(
                pendingDraft == null ? List.of() : pendingDraft.changes(),
                reverseChanges.blockChanges(),
                forwardChanges.blockChanges(),
                request.bounds(),
                request.restoreMode(),
                request.bounds()::contains,
                hardScope
        );
        List<StoredEntityChange> partialEntityChanges = this.partialRestoreEntityPlanner.plan(
                pendingDraft == null ? List.of() : pendingDraft.entityChanges(),
                reverseChanges.entityChanges(),
                forwardChanges.entityChanges(),
                request.bounds(),
                request.restoreMode(),
                hardScope
        );
        Instant now = Instant.now();
        RecoveryDraft draft = new RecoveryDraft(
                project.id().toString(),
                activeVariant.id(),
                activeVariant.headVersionId(),
                request.actor() == null || request.actor().isBlank() ? "Lumi" : request.actor(),
                WorldMutationSource.RESTORE,
                now,
                now,
                partialChanges,
                partialEntityChanges
        );
        LumaDebugLog.log(
                project,
                "restore",
                "Partial restore for project {} target {} filtered {} lineage changes to {} region changes",
                project.name(),
                targetVersion.id(),
                lineageChangeCount,
                partialChanges.size() + partialEntityChanges.size()
        );
        return new PartialRestoreDraft(RestorePlanMode.PATCH_REPLAY, draft);
    }

    private PartialRestoreDraft applyEntityTypeSelection(
            PartialRestoreDraft draft,
            RestoreEntityTypeSelection entityTypeSelection
    ) {
        RestoreEntityTypeSelection selection = entityTypeSelection == null
                ? RestoreEntityTypeSelection.includeAll()
                : entityTypeSelection;
        if (draft == null || draft.draft() == null || selection.excludedEntityTypes().isEmpty()) {
            return draft;
        }
        List<StoredEntityChange> filteredEntities = draft.draft().entityChanges().stream()
                .filter(change -> change == null || selection.includes(change.entityType()))
                .toList();
        if (filteredEntities.size() == draft.draft().entityChanges().size()) {
            return draft;
        }
        RecoveryDraft filteredDraft = new RecoveryDraft(
                draft.draft().projectId(),
                draft.draft().variantId(),
                draft.draft().baseVersionId(),
                draft.draft().actor(),
                draft.draft().mutationSource(),
                draft.draft().startedAt(),
                draft.draft().updatedAt(),
                draft.draft().changes(),
                filteredEntities
        );
        return new PartialRestoreDraft(draft.mode(), filteredDraft);
    }

    private PartialRestoreDraft buildTargetStateDraft(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            ProjectVariant activeVariant,
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft,
            PartialRestoreRequest request,
            Predicate<BlockPoint> hardScope,
            int worldMinY,
            int worldMaxY,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        ProjectVersion currentHead = versions.stream()
                .filter(version -> version.id().equals(activeVariant.headVersionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active variant head is missing: " + activeVariant.headVersionId()));
        PartialRestoreTargetStatePlanner.Plan plan = this.targetStatePlanner.plan(
                layout,
                project,
                versions,
                currentHead,
                targetVersion,
                pendingDraft,
                request.bounds(),
                request.restoreMode(),
                worldMinY,
                worldMaxY,
                progressSink,
                hardScope
        );
        Instant now = Instant.now();
        RecoveryDraft draft = new RecoveryDraft(
                project.id().toString(),
                activeVariant.id(),
                activeVariant.headVersionId(),
                request.actor() == null || request.actor().isBlank() ? "Lumi" : request.actor(),
                WorldMutationSource.RESTORE,
                now,
                now,
                plan.blockChanges(),
                plan.entityChanges()
        );
        LumaDebugLog.log(
                project,
                "restore",
                "Partial restore for project {} target {} used target-state planning with {} changes",
                project.name(),
                targetVersion.id(),
                plan.blockChanges().size() + plan.entityChanges().size()
        );
        return new PartialRestoreDraft(RestorePlanMode.TARGET_STATE, draft);
    }

    private List<PreparedChunkBatch> decodeStoredChanges(
            ServerLevel level,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            boolean applyNewValues
    ) throws IOException {
        return this.decodeStoredChangesAnalyzed(level, changes, entityChanges, applyNewValues).batches();
    }

    private PreparedWorldChangeBatches decodeStoredChangesAnalyzed(
            ServerLevel level,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            boolean applyNewValues
    ) throws IOException {
        try (var ignored = LumaLoadLog.measure(
                "restore",
                "WorldChangeBatchPreparer.prepareStoredChanges",
                "blocks=" + changes.size()
                        + ", entities=" + (entityChanges == null ? 0 : entityChanges.size())
                        + ", applyNewValues=" + applyNewValues
        )) {
            PreparedWorldChangeBatches analyzed = this.batchPreparer.prepareAnalyzed(
                    level,
                    changes,
                    entityChanges,
                    applyNewValues,
                    WorldChangeBatchPreparer.ProgressListener.NO_OP,
                    EntityApplyMode.DELTA
            );
            LumaDebugLog.log(
                    "restore",
                    "Decoded {} block and {} entity stored changes into {} grouped chunk batches using {} values",
                    changes.size(),
                    entityChanges == null ? 0 : entityChanges.size(),
                    analyzed.batches().size(),
                    applyNewValues ? "new" : "old"
            );
            return analyzed;
        }
    }

    record PartialRestoreDraft(RestorePlanMode mode, RecoveryDraft draft) {
    }
}
