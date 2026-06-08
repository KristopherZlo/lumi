package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumiTestFailpoints;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.PendingRestoreCompletion;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.VariantRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * Publishes restore metadata after prepared world apply has succeeded.
 */
final class RestoreCompletionCoordinator {

    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final PartialRestoreDraftRewriter partialRestoreDraftRewriter = new PartialRestoreDraftRewriter();
    private final UndoRedoHistoryManager undoRedoHistoryManager = UndoRedoHistoryManager.getInstance();

    void completePartialRestore(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            RecoveryDraft pendingDraft,
            PartialRestoreRequest request,
            RecoveryDraft partialDraft,
            int batchCount
    ) throws IOException {
        Instant now = Instant.now();
        RecoveryDraft mergedDraft = this.partialRestoreDraftRewriter.mergeRestoredChanges(
                pendingDraft,
                partialDraft,
                now
        );
        RecoveryDraft durableOperationDraft = mergedDraft == null
                ? this.partialRestoreDraftRewriter.emptyRestoredDraft(pendingDraft, partialDraft, now)
                : mergedDraft;
        this.recoveryRepository.saveOperationDraft(layout, durableOperationDraft);
        this.recoveryRepository.savePendingRestoreCompletion(layout, PendingRestoreCompletion.partial(
                project.id().toString(),
                partialDraft.variantId(),
                request.targetVersionId(),
                now,
                request.bounds(),
                request.restoreMode()
        ));
        this.recordPartialRestoreUndoAction(level, project, request, partialDraft);
        this.partialRestoreDraftRewriter.saveDraftOrDelete(layout, mergedDraft);
        if (mergedDraft != null) {
            HistoryCaptureManager.getInstance().markPersistedDraftCurrentRun(level.getServer(), project.id().toString());
        }
        this.recoveryRepository.deleteOperationDraft(layout);
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "partial-restore-completed",
                "Partial restore applied target state as pending draft changes",
                request.targetVersionId(),
                partialDraft.variantId()
        ));
        this.recoveryRepository.deletePendingRestoreCompletion(layout);
        HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());
        LumaMod.LOGGER.info(
                "Completed partial restore for project {} to version {} with {} chunk batches and {} changes",
                project.name(),
                request.targetVersionId(),
                batchCount,
                partialDraft.totalChangeCount()
        );
    }

    void completeRestore(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVariant> variants,
            ProjectVariant targetVariant,
            ProjectVersion version,
            int batchCount,
            RestoreUndoAction restoreUndoAction
    ) throws IOException {
        Instant now = Instant.now();
        this.recoveryRepository.savePendingRestoreCompletion(layout, PendingRestoreCompletion.full(
                project.id().toString(),
                targetVariant.id(),
                version.id(),
                now
        ));
        List<ProjectVariant> latestVariants = this.variantRepository.loadAll(layout);
        LumiTestFailpoints.hit(LumiTestFailpoints.BEFORE_RESTORE_METADATA_WRITE);
        this.variantRepository.save(layout, this.replaceVariantHead(
                latestVariants.isEmpty() ? variants : latestVariants,
                targetVariant.id(),
                version.id()
        ));
        BuildProject updatedProject = targetVariant.id().equals(project.activeVariantId())
                ? project.withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION).withUpdatedAt(now)
                : project.withActiveVariantId(targetVariant.id(), now)
                        .withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION);
        this.projectRepository.save(layout, updatedProject);
        this.recoveryRepository.deleteDraft(layout);
        this.undoRedoHistoryManager.clearProject(project.id().toString());
        this.recordRestoreUndoAction(restoreUndoAction, now);
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "restore-completed",
                "Restored project state and reset branch head to version " + version.id(),
                version.id(),
                targetVariant.id()
        ));
        this.recoveryRepository.deletePendingRestoreCompletion(layout);
        HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());
        LumaMod.LOGGER.info(
                "Completed restore for project {} to version {} on variant {} with {} prepared chunk batches",
                project.name(),
                version.id(),
                targetVariant.id(),
                batchCount
        );
    }

    private void recordPartialRestoreUndoAction(
            ServerLevel level,
            BuildProject project,
            PartialRestoreRequest request,
            RecoveryDraft partialDraft
    ) {
        if (partialDraft == null || partialDraft.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        this.undoRedoHistoryManager.recordAction(
                project.id().toString(),
                level.dimension().identifier().toString(),
                "partial-restore-" + request.targetVersionId() + "-" + UUID.randomUUID(),
                partialDraft.actor(),
                partialDraft.changes(),
                partialDraft.entityChanges(),
                now
        );
    }

    private void recordRestoreUndoAction(RestoreUndoAction action, Instant now) {
        if (action == null || action.isEmpty()) {
            return;
        }
        this.undoRedoHistoryManager.recordAction(
                action.projectId(),
                action.dimensionId(),
                action.actionId(),
                action.actor(),
                action.changes(),
                action.entityChanges(),
                now
        );
    }

    private List<ProjectVariant> replaceVariantHead(
            List<ProjectVariant> variants,
            String targetVariantId,
            String targetVersionId
    ) {
        List<ProjectVariant> updated = new ArrayList<>();
        for (ProjectVariant variant : variants) {
            if (!variant.id().equals(targetVariantId)) {
                updated.add(variant);
                continue;
            }
            updated.add(new ProjectVariant(
                    variant.id(),
                    variant.name(),
                    variant.baseVersionId(),
                    targetVersionId,
                    variant.main(),
                    variant.createdAt()
            ));
        }
        return updated;
    }
}
