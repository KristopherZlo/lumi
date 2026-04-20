package io.github.luma.domain.service;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.SnapshotRef;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.BlockChangeApplier;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.PatchRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.SnapshotRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;

public final class RestoreService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final BaselineChunkRepository baselineChunkRepository = new BaselineChunkRepository();
    private final SnapshotRepository snapshotRepository = new SnapshotRepository();
    private final PatchRepository patchRepository = new PatchRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final VersionService versionService = new VersionService();

    public ProjectVersion restore(ServerLevel level, String projectName, String versionId) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        HistoryCaptureManager.getInstance().finalizeProjectSession(level.getServer(), project.id().toString());

        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVersion version = this.resolveVersion(project, versions, variants, versionId);
        var draft = this.recoveryRepository.loadDraft(layout);
        if (project.settings().safetySnapshotBeforeRestore() && draft.isPresent() && !draft.get().isEmpty()) {
            this.versionService.saveVersion(level, projectName, "", "Luma", VersionKind.RESTORE);
        }
        RestoreChain chain = this.resolveChain(versions, version);

        WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () -> {
            try {
                if (version.snapshotId() != null && !version.snapshotId().isBlank()) {
                    SnapshotRef snapshot = new SnapshotRef(
                            version.snapshotId(),
                            project.id().toString(),
                            version.snapshotId() + ".nbt.lz4",
                            0,
                            0L,
                            version.createdAt()
                    );
                    this.snapshotRepository.restore(layout, snapshot, level);
                    this.baselineChunkRepository.restoreMissing(
                            layout,
                            level,
                            this.snapshotRepository.loadChunks(layout, snapshot)
                    );
                } else {
                    this.snapshotRepository.restore(layout, new SnapshotRef(
                            chain.anchor().snapshotId(),
                            project.id().toString(),
                            chain.anchor().snapshotId() + ".nbt.lz4",
                            0,
                            0L,
                            chain.anchor().createdAt()
                    ), level);

                    for (ProjectVersion patchVersion : chain.patchVersions()) {
                        for (String patchId : patchVersion.patchIds()) {
                            BlockChangeApplier.applyChanges(level, this.patchRepository.loadChanges(layout, patchId));
                        }
                    }
                }
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });

        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                Instant.now(),
                "version-restored",
                "Restored project to version " + version.id(),
                version.id(),
                version.variantId()
        ));
        return version;
    }

    private ProjectVersion resolveVersion(
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            String versionId
    ) {
        if (versionId != null && !versionId.isBlank()) {
            return versions.stream()
                    .filter(candidate -> candidate.id().equals(versionId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));
        }

        String activeVariantId = project.activeVariantId();
        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(activeVariantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active variant is missing for " + project.name()));

        return versions.stream()
                .filter(candidate -> candidate.id().equals(activeVariant.headVersionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Variant head version is missing: " + activeVariant.headVersionId()));
    }

    private RestoreChain resolveChain(List<ProjectVersion> versions, ProjectVersion targetVersion) {
        Map<String, ProjectVersion> versionMap = new HashMap<>();
        for (ProjectVersion version : versions) {
            versionMap.put(version.id(), version);
        }

        List<ProjectVersion> patchVersions = new ArrayList<>();
        ProjectVersion cursor = targetVersion;
        while (cursor != null && (cursor.snapshotId() == null || cursor.snapshotId().isBlank())) {
            patchVersions.add(cursor);
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionMap.get(cursor.parentVersionId());
        }

        if (cursor == null) {
            throw new IllegalArgumentException("No checkpoint snapshot found for version " + targetVersion.id());
        }

        patchVersions.sort(Comparator.comparing(ProjectVersion::createdAt));
        return new RestoreChain(cursor, patchVersions);
    }

    private record RestoreChain(ProjectVersion anchor, List<ProjectVersion> patchVersions) {
    }
}
