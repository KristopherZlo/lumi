package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PendingRestoreCompletion;
import io.github.luma.domain.model.PendingRestoreCompletionKind;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
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
import net.minecraft.core.BlockPos;
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

        ProjectVersion version = this.versionRepository.load(layout, completion.targetVersionId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Pending restore completion target version is missing: " + completion.targetVersionId()
                ));
        this.publishHead(layout, project, completion.variantId(), version.id());
        if (completion.kind() == PendingRestoreCompletionKind.PARTIAL_RESTORE) {
            this.rewritePendingDraftAfterPartialRestore(layout, completion.partialBounds(), completion.partialMode());
            this.appendJournalIfMissing(
                    layout,
                    "partial-restore-completed",
                    version.id(),
                    completion.variantId(),
                    "Partial restore wrote a new version from selected region"
            );
        } else {
            this.recoveryRepository.deleteDraft(layout);
            this.appendJournalIfMissing(
                    layout,
                    "restore-completed",
                    version.id(),
                    completion.variantId(),
                    "Restored project state and reset branch head to version " + version.id()
            );
        }

        this.recoveryRepository.deletePendingRestoreCompletion(layout);
        if (server != null) {
            HistoryCaptureManager.getInstance().invalidateProjectCache(server);
        }
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
                    variant.createdAt()
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

    private void rewritePendingDraftAfterPartialRestore(
            ProjectLayout layout,
            Bounds3i bounds,
            PartialRestoreMode mode
    ) throws IOException {
        RecoveryDraft pendingDraft = this.recoveryRepository.loadDraft(layout).orElse(null);
        if (pendingDraft == null || pendingDraft.isEmpty()) {
            this.recoveryRepository.deleteDraft(layout);
            return;
        }
        PartialRestoreMode effectiveMode = mode == null ? PartialRestoreMode.SELECTED_AREA : mode;
        List<StoredBlockChange> remaining = pendingDraft.changes().stream()
                .filter(change -> !effectiveMode.includes(bounds.contains(change.pos())))
                .toList();
        List<StoredEntityChange> remainingEntities = pendingDraft.entityChanges().stream()
                .filter(change -> !effectiveMode.includes(this.entityChangeInside(change, bounds)))
                .toList();
        if (remaining.isEmpty() && remainingEntities.isEmpty()) {
            this.recoveryRepository.deleteDraft(layout);
            return;
        }
        this.recoveryRepository.saveDraft(layout, new RecoveryDraft(
                pendingDraft.projectId(),
                pendingDraft.variantId(),
                pendingDraft.baseVersionId(),
                pendingDraft.actor(),
                pendingDraft.mutationSource(),
                pendingDraft.startedAt(),
                Instant.now(),
                remaining,
                remainingEntities
        ));
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

    private boolean entityChangeInside(StoredEntityChange change, Bounds3i bounds) {
        if (change == null || bounds == null) {
            return false;
        }
        BlockPos pos = change.newValue() == null
                ? change.oldValue().blockPos()
                : change.newValue().blockPos();
        return bounds.contains(BlockPoint.from(pos));
    }
}
