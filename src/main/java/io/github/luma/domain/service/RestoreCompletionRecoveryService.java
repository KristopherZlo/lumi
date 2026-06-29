package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.PendingRestoreCompletion;
import io.github.luma.domain.model.PendingRestoreCompletionKind;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;

/**
 * Completes restore metadata publication after world apply has already
 * succeeded but the original completion callback was interrupted.
 */
final class RestoreCompletionRecoveryService {

    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final PartialRestoreDraftRewriter partialRestoreDraftRewriter = new PartialRestoreDraftRewriter();

    void completePending(
            ProjectLayout layout,
            BuildProject project,
            MinecraftServer server
    ) throws IOException {
        PendingRestoreCompletion completion = this.recoveryRepository
                .loadPendingRestoreCompletion(layout)
                .orElse(null);
        if (completion == null) {
            return;
        }
        if (!project.id().toString().equals(completion.projectId())) {
            LumaMod.LOGGER.warn(
                    "Keeping pending restore completion hidden for project {} because it belongs to {}",
                    project.name(),
                    completion.projectId()
            );
            return;
        }

        if (completion.kind() == PendingRestoreCompletionKind.PARTIAL_RESTORE) {
            this.completePendingPartialRestore(layout, project, completion, server);
            return;
        }

        ProjectVersion version = this.versionRepository.load(layout, completion.targetVersionId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Pending restore completion target version is missing: " + completion.targetVersionId()
                ));
        this.publishHead(layout, project, completion.variantId(), version.id());
        this.recoveryRepository.deleteDraft(layout);
        this.appendJournalIfMissing(
                layout,
                "restore-completed",
                version.id(),
                completion.variantId(),
                "Restored project state and reset branch head to version " + version.id()
        );

        this.recoveryRepository.deletePendingRestoreCompletion(layout);
        if (server != null) {
            HistoryCaptureManager.getInstance().invalidateProjectCache(server);
        }
    }

    private void completePendingPartialRestore(
            ProjectLayout layout,
            BuildProject project,
            PendingRestoreCompletion completion,
            MinecraftServer server
    ) throws IOException {
        if (this.promotePartialRestoreOperationDraft(layout, project)) {
            this.appendJournalIfMissing(
                    layout,
                    "partial-restore-completed",
                    completion.targetVersionId(),
                    completion.variantId(),
                    "Partial restore applied target state as pending draft changes"
            );
            this.recoveryRepository.deletePendingRestoreCompletion(layout);
            if (server != null) {
                HistoryCaptureManager.getInstance().invalidateProjectCache(server);
            }
            return;
        }

        ProjectVersion legacyStagedVersion = this.versionRepository.load(layout, completion.targetVersionId())
                .filter(version -> version.versionKind() == VersionKind.PARTIAL_RESTORE)
                .orElse(null);
        if (legacyStagedVersion != null) {
            this.publishHead(layout, project, completion.variantId(), legacyStagedVersion.id());
            this.partialRestoreDraftRewriter.preserveOutsideRestoredRegion(
                    layout,
                    this.recoveryRepository.loadDraft(layout).orElse(null),
                    completion.partialBounds(),
                    completion.partialMode()
            );
            this.appendJournalIfMissing(
                    layout,
                    "partial-restore-completed",
                    legacyStagedVersion.id(),
                    completion.variantId(),
                    "Partial restore wrote a new version from selected region"
            );
        }

        this.recoveryRepository.deletePendingRestoreCompletion(layout);
        if (server != null) {
            HistoryCaptureManager.getInstance().invalidateProjectCache(server);
        }
    }

    private boolean promotePartialRestoreOperationDraft(ProjectLayout layout, BuildProject project) throws IOException {
        RecoveryDraft operationDraft = this.recoveryRepository.loadOperationDraft(layout).orElse(null);
        if (operationDraft == null) {
            return false;
        }
        if (!project.id().toString().equals(operationDraft.projectId())) {
            LumaMod.LOGGER.warn(
                    "Keeping partial restore operation draft for project {} hidden because it belongs to project id {}",
                    project.name(),
                    operationDraft.projectId()
            );
            return false;
        }
        if (operationDraft.isEmpty()) {
            this.recoveryRepository.deleteDraft(layout);
        } else {
            this.recoveryRepository.saveDraft(layout, operationDraft);
        }
        this.recoveryRepository.deleteOperationDraft(layout);
        return true;
    }

    private void publishHead(
            ProjectLayout layout,
            BuildProject project,
            String variantId,
            String versionId
    ) throws IOException {
        Instant now = Instant.now();
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        List<ProjectVariant> updated = new ArrayList<>();
        boolean found = false;
        for (ProjectVariant variant : variants) {
            if (!variant.id().equals(variantId)) {
                updated.add(variant);
                continue;
            }
            found = true;
            updated.add(new ProjectVariant(
                    variant.id(),
                    variant.name(),
                    variant.baseVersionId(),
                    versionId,
                    variant.main(),
                    variant.createdAt(),
                    variant.switchKey()
            ));
        }
        if (!found) {
            throw new IllegalArgumentException("Pending restore completion variant is missing: " + variantId);
        }
        this.variantRepository.save(layout, updated);
        BuildProject latestProject = this.projectRepository.load(layout).orElse(project);
        this.projectRepository.save(layout, latestProject
                .withActiveVariantId(variantId, now)
                .withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION));
    }

    private void appendJournalIfMissing(
            ProjectLayout layout,
            String action,
            String versionId,
            String variantId,
            String message
    ) throws IOException {
        boolean exists = this.recoveryRepository.loadJournal(layout).stream()
                .anyMatch(entry -> action.equals(entry.type())
                        && versionId.equals(entry.versionId())
                        && variantId.equals(entry.variantId()));
        if (!exists) {
            this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                    Instant.now(),
                    action,
                    message,
                    versionId,
                    variantId
            ));
        }
    }

}
