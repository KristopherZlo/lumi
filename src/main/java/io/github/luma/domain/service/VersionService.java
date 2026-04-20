package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPatch;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.SnapshotRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;

public final class VersionService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final SnapshotRepository snapshotRepository = new SnapshotRepository();
    private final PatchRepository patchRepository = new PatchRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();

    public ProjectVersion saveVersion(ServerLevel level, String projectName, String message, String author) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        HistoryCaptureManager.getInstance().finalizeProjectSession(level.getServer(), project.id().toString());

        RecoveryDraft draft = this.recoveryRepository.loadDraft(layout)
                .orElseThrow(() -> new IllegalArgumentException("No pending tracked changes for " + projectName));
        if (draft.isEmpty()) {
            throw new IllegalArgumentException("No pending tracked changes for " + projectName);
        }

        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(project.activeVariantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active variant is missing for " + projectName));

        int nextIndex = versions.size() + 1;
        Instant now = Instant.now();
        String versionId = ProjectService.versionId(nextIndex);
        String patchId = ProjectService.patchId(nextIndex);
        String snapshotId = this.shouldCreateCheckpoint(project, versions, activeVariant, draft) ? ProjectService.snapshotId(nextIndex) : "";

        BlockPatch patch = ChangeStatsFactory.createPatch(
                patchId,
                project.id().toString(),
                versionId,
                patchId + ".json.lz4",
                draft.changes()
        );
        this.patchRepository.save(layout, patch, draft.changes());

        if (!snapshotId.isBlank()) {
            this.snapshotRepository.capture(layout, project.id().toString(), snapshotId, project.bounds(), level, now);
        }

        ChangeStats stats = ChangeStatsFactory.summarize(draft.changes());
        ProjectVersion version = new ProjectVersion(
                versionId,
                project.id().toString(),
                activeVariant.id(),
                activeVariant.headVersionId(),
                snapshotId,
                List.of(patch.id()),
                project.isLegacySnapshotProject() ? VersionKind.LEGACY : VersionKind.MANUAL,
                author,
                message == null || message.isBlank() ? "Saved version" : message,
                stats,
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                now
        );

        this.versionRepository.save(layout, version);
        this.variantRepository.save(layout, this.replaceVariant(variants, new ProjectVariant(
                activeVariant.id(),
                activeVariant.name(),
                activeVariant.baseVersionId(),
                version.id(),
                activeVariant.main(),
                activeVariant.createdAt()
        )));
        this.projectRepository.save(layout, project.withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION).withUpdatedAt(now));
        this.recoveryRepository.deleteDraft(layout);
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "version-saved",
                "Saved version from tracked changes",
                version.id(),
                activeVariant.id()
        ));

        return version;
    }

    private boolean shouldCreateCheckpoint(
            BuildProject project,
            List<ProjectVersion> versions,
            ProjectVariant activeVariant,
            RecoveryDraft draft
    ) {
        if (project.isLegacySnapshotProject()) {
            return true;
        }

        if ((versions.size() + 1) % project.settings().snapshotEveryVersions() == 0) {
            return true;
        }

        long changedBlocks = draft.changes().size();
        if (project.bounds().volume() > 0 && (double) changedBlocks / (double) project.bounds().volume() >= project.settings().snapshotVolumeThreshold()) {
            return true;
        }

        return this.patchChainLength(versions, activeVariant.headVersionId()) >= 10;
    }

    private int patchChainLength(List<ProjectVersion> versions, String headVersionId) {
        Map<String, ProjectVersion> versionMap = new HashMap<>();
        for (ProjectVersion version : versions) {
            versionMap.put(version.id(), version);
        }

        int length = 0;
        String cursor = headVersionId;
        while (cursor != null && !cursor.isBlank()) {
            ProjectVersion version = versionMap.get(cursor);
            if (version == null) {
                break;
            }
            if (version.snapshotId() != null && !version.snapshotId().isBlank()) {
                break;
            }
            length += version.patchIds().size();
            cursor = version.parentVersionId();
        }
        return length;
    }

    private List<ProjectVariant> replaceVariant(List<ProjectVariant> variants, ProjectVariant updatedVariant) {
        List<ProjectVariant> result = new ArrayList<>();
        for (ProjectVariant variant : variants) {
            result.add(variant.id().equals(updatedVariant.id()) ? updatedVariant : variant);
        }
        return result;
    }
}
