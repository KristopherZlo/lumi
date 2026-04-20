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
    private final PreviewService previewService = new PreviewService();

    public ProjectVersion saveVersion(ServerLevel level, String projectName, String message, String author) throws IOException {
        return this.saveVersion(level, projectName, message, author, VersionKind.MANUAL);
    }

    public ProjectVersion refreshPreview(ServerLevel level, String projectName, String versionId) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        ProjectVersion version = this.versionRepository.load(layout, versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));
        PreviewInfo preview = this.previewService.capture(layout, versionId, project.bounds(), level);
        ProjectVersion updated = new ProjectVersion(
                version.id(),
                version.projectId(),
                version.variantId(),
                version.parentVersionId(),
                version.snapshotId(),
                version.patchIds(),
                version.versionKind(),
                version.author(),
                version.message(),
                version.stats(),
                preview,
                version.sourceInfo(),
                version.createdAt()
        );
        this.versionRepository.save(layout, updated);
        return updated;
    }

    public ProjectVersion saveVersion(ServerLevel level, String projectName, String message, String author, VersionKind versionKind) throws IOException {
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
        PreviewInfo preview = PreviewInfo.none();
        if (project.settings().previewGenerationEnabled()) {
            try {
                preview = this.previewService.capture(layout, versionId, project.bounds(), level);
            } catch (Exception ignored) {
                preview = PreviewInfo.none();
            }
        }
        ProjectVersion version = new ProjectVersion(
                versionId,
                project.id().toString(),
                activeVariant.id(),
                activeVariant.headVersionId(),
                snapshotId,
                List.of(patch.id()),
                project.isLegacySnapshotProject() ? VersionKind.LEGACY : versionKind,
                author,
                this.resolveMessage(message, project.isLegacySnapshotProject() ? VersionKind.LEGACY : versionKind),
                stats,
                preview,
                this.resolveSourceInfo(project.isLegacySnapshotProject() ? VersionKind.LEGACY : versionKind),
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
                this.resolveJournalMessage(version.versionKind()),
                version.id(),
                activeVariant.id()
        ));
        HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());

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

    private String resolveMessage(String message, VersionKind versionKind) {
        if (message != null && !message.isBlank()) {
            return message;
        }

        return switch (versionKind) {
            case RECOVERY -> "Recovered draft";
            case LEGACY -> "Migrated legacy save";
            case RESTORE -> "Restore safety checkpoint";
            case INITIAL, MANUAL -> "Saved version";
        };
    }

    private ExternalSourceInfo resolveSourceInfo(VersionKind versionKind) {
        return switch (versionKind) {
            case RECOVERY -> ExternalSourceInfo.recovery();
            case RESTORE -> ExternalSourceInfo.restore();
            case INITIAL, MANUAL, LEGACY -> ExternalSourceInfo.manual();
        };
    }

    private String resolveJournalMessage(VersionKind versionKind) {
        return switch (versionKind) {
            case RECOVERY -> "Saved recovery draft as a new version";
            case LEGACY -> "Saved a new version while migrating a legacy snapshot project";
            case RESTORE -> "Saved restore checkpoint version";
            case INITIAL, MANUAL -> "Saved version from tracked changes";
        };
    }
}
