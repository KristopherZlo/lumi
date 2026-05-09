package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.SnapshotReader;
import io.github.luma.storage.repository.SnapshotWriter;
import io.github.luma.storage.repository.VersionRepository;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Performs one-time storage upgrades before hot history workflows run.
 */
public final class HistoryMigrationService {

    private static final int SNAPSHOT_MAGIC = 0x4C534E50;
    private static final int SNAPSHOT_CONTENT_REF_VERSION = 7;

    private final ProjectRepository projectRepository;
    private final VersionRepository versionRepository;
    private final PatchMetaRepository patchMetaRepository;
    private final PatchDataRepository patchDataRepository;
    private final SnapshotReader snapshotReader;
    private final SnapshotWriter snapshotWriter;
    private final RecoveryRepository recoveryRepository;

    public HistoryMigrationService() {
        this(
                new ProjectRepository(),
                new VersionRepository(),
                new PatchMetaRepository(),
                new PatchDataRepository(),
                new SnapshotReader(),
                new SnapshotWriter(),
                new RecoveryRepository()
        );
    }

    HistoryMigrationService(
            ProjectRepository projectRepository,
            VersionRepository versionRepository,
            PatchMetaRepository patchMetaRepository,
            PatchDataRepository patchDataRepository,
            SnapshotReader snapshotReader,
            SnapshotWriter snapshotWriter,
            RecoveryRepository recoveryRepository
    ) {
        this.projectRepository = projectRepository;
        this.versionRepository = versionRepository;
        this.patchMetaRepository = patchMetaRepository;
        this.patchDataRepository = patchDataRepository;
        this.snapshotReader = snapshotReader;
        this.snapshotWriter = snapshotWriter;
        this.recoveryRepository = recoveryRepository;
    }

    public MigrationReport migrate(ProjectLayout layout, BuildProject project) throws IOException {
        if (layout == null || project == null) {
            return MigrationReport.empty();
        }

        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        boolean projectUpdated = project.schemaVersion() < BuildProject.CURRENT_SCHEMA_VERSION;
        int patchCount = this.migratePatches(layout, versions);
        int snapshotCount = this.migrateSnapshots(layout, versions);
        int recoveryCount = projectUpdated ? this.migrateRecovery(layout) : 0;
        if (projectUpdated) {
            this.projectRepository.save(
                    layout,
                    project.withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION).withUpdatedAt(Instant.now())
            );
        }

        MigrationReport report = new MigrationReport(patchCount, snapshotCount, recoveryCount, projectUpdated);
        if (!report.isEmpty()) {
            LumaMod.LOGGER.info(
                    "Migrated history storage for project {}: patches={} snapshots={} recovery={} schemaUpdated={}",
                    project.name(),
                    patchCount,
                    snapshotCount,
                    recoveryCount,
                    projectUpdated
            );
        }
        return report;
    }

    private int migratePatches(ProjectLayout layout, List<ProjectVersion> versions) throws IOException {
        Set<String> patchIds = new LinkedHashSet<>();
        for (ProjectVersion version : versions) {
            patchIds.addAll(version.patchIds() == null ? List.of() : version.patchIds());
        }

        int migrated = 0;
        for (String patchId : patchIds) {
            Optional<PatchMetadata> metadata = this.patchMetaRepository.load(layout, patchId);
            if (metadata.isEmpty() || !this.patchNeedsMigration(metadata.get())) {
                continue;
            }
            var worldChanges = this.patchDataRepository.loadWorldChanges(layout, metadata.get());
            PatchMetadata rewritten = this.patchDataRepository.writePayload(
                    layout,
                    patchId,
                    metadata.get().projectId(),
                    metadata.get().versionId(),
                    worldChanges.blockChanges(),
                    worldChanges.entityChanges()
            );
            this.patchMetaRepository.save(layout, rewritten);
            migrated += 1;
        }
        return migrated;
    }

    private boolean patchNeedsMigration(PatchMetadata metadata) {
        if (metadata.chunks() == null || metadata.chunks().isEmpty()) {
            return metadata.stats() == null || metadata.stats().changedBlocks() > 0;
        }
        return metadata.chunks().stream()
                .anyMatch(chunk -> chunk.changeCount() > 0 && chunk.sectionFingerprints().isEmpty());
    }

    private int migrateSnapshots(ProjectLayout layout, List<ProjectVersion> versions) throws IOException {
        Set<String> snapshotIds = new LinkedHashSet<>();
        for (ProjectVersion version : versions) {
            if (version.snapshotId() != null && !version.snapshotId().isBlank()) {
                snapshotIds.add(version.snapshotId());
            }
        }

        int migrated = 0;
        for (String snapshotId : snapshotIds) {
            if (!Files.exists(layout.snapshotFile(snapshotId)) || !this.snapshotNeedsMigration(layout, snapshotId)) {
                continue;
            }
            SnapshotData snapshot = this.snapshotReader.readFile(layout.snapshotFile(snapshotId));
            this.snapshotWriter.writeFile(layout, layout.snapshotFile(snapshotId), snapshot);
            migrated += 1;
        }
        return migrated;
    }

    private boolean snapshotNeedsMigration(ProjectLayout layout, String snapshotId) throws IOException {
        if (!this.isSnapshotContentRefVersion(layout.snapshotFile(snapshotId))) {
            return true;
        }
        var metadata = this.snapshotReader.loadSectionIndex(layout.snapshotFile(snapshotId));
        return metadata.sectionCount() > 0 && metadata.chunks().stream()
                .anyMatch(chunk -> chunk.contentRefs().isEmpty());
    }

    private boolean isSnapshotContentRefVersion(java.nio.file.Path snapshotFile) throws IOException {
        if (!Files.exists(snapshotFile) || Files.size(snapshotFile) < 8L) {
            return false;
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(snapshotFile)))) {
            return input.readInt() == SNAPSHOT_MAGIC && input.readInt() >= SNAPSHOT_CONTENT_REF_VERSION;
        }
    }

    private int migrateRecovery(ProjectLayout layout) throws IOException {
        int migrated = 0;
        Optional<RecoveryDraft> draft = this.recoveryRepository.loadDraft(layout);
        if (draft.isPresent()) {
            this.recoveryRepository.saveDraft(layout, draft.get());
            migrated += 1;
        }
        Optional<RecoveryDraft> operationDraft = this.recoveryRepository.loadOperationDraft(layout);
        if (operationDraft.isPresent()) {
            this.recoveryRepository.saveOperationDraft(layout, operationDraft.get());
            migrated += 1;
        }
        return migrated;
    }

    public record MigrationReport(
            int patchCount,
            int snapshotCount,
            int recoveryCount,
            boolean projectUpdated
    ) {

        public static MigrationReport empty() {
            return new MigrationReport(0, 0, 0, false);
        }

        public boolean isEmpty() {
            return this.patchCount <= 0 && this.snapshotCount <= 0 && this.recoveryCount <= 0 && !this.projectUpdated;
        }
    }
}
