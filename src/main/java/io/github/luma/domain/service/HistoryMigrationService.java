package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.BaselineChunkRepository;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final BaselineChunkRepository baselineChunkRepository;

    public HistoryMigrationService() {
        this(
                new ProjectRepository(),
                new VersionRepository(),
                new PatchMetaRepository(),
                new PatchDataRepository(),
                new SnapshotReader(),
                new SnapshotWriter(),
                new RecoveryRepository(),
                new BaselineChunkRepository()
        );
    }

    HistoryMigrationService(
            ProjectRepository projectRepository,
            VersionRepository versionRepository,
            PatchMetaRepository patchMetaRepository,
            PatchDataRepository patchDataRepository,
            SnapshotReader snapshotReader,
            SnapshotWriter snapshotWriter,
            RecoveryRepository recoveryRepository,
            BaselineChunkRepository baselineChunkRepository
    ) {
        this.projectRepository = projectRepository;
        this.versionRepository = versionRepository;
        this.patchMetaRepository = patchMetaRepository;
        this.patchDataRepository = patchDataRepository;
        this.snapshotReader = snapshotReader;
        this.snapshotWriter = snapshotWriter;
        this.recoveryRepository = recoveryRepository;
        this.baselineChunkRepository = baselineChunkRepository;
    }

    public MigrationReport migrate(ProjectLayout layout, BuildProject project) throws IOException {
        if (layout == null || project == null) {
            return MigrationReport.empty();
        }

        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        boolean projectUpdated = project.schemaVersion() < BuildProject.CURRENT_SCHEMA_VERSION;
        int patchCount = this.migratePatches(layout, versions);
        int snapshotCount = this.migrateSnapshots(layout, versions);
        if (project.tracksWholeDimension()) {
            snapshotCount += this.repairWholeDimensionEntityCheckpoints(layout, versions);
        }
        int recoveryCount = projectUpdated ? this.migrateRecovery(layout) : 0;
        int orphanBaselineCount = project.tracksWholeDimension()
                ? this.quarantineOrphanBaselines(layout, versions)
                : 0;
        if (projectUpdated) {
            this.projectRepository.save(
                    layout,
                    project.withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION).withUpdatedAt(Instant.now())
            );
        }

        MigrationReport report = new MigrationReport(patchCount, snapshotCount, recoveryCount, projectUpdated);
        if (orphanBaselineCount > 0) {
            LumaMod.LOGGER.warn(
                    "Quarantined {} unreferenced baseline chunks for project {}",
                    orphanBaselineCount,
                    project.name()
            );
        }
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
        Set<Path> snapshotFiles = new LinkedHashSet<>();
        for (ProjectVersion version : versions) {
            if (version.snapshotId() != null && !version.snapshotId().isBlank()) {
                snapshotFiles.add(layout.snapshotFile(version.snapshotId()));
            }
            if (version.entityCheckpointId() != null && !version.entityCheckpointId().isBlank()) {
                snapshotFiles.add(layout.entityCheckpointFile(version.entityCheckpointId()));
            }
        }

        int migrated = 0;
        for (Path snapshotFile : snapshotFiles) {
            if (!Files.exists(snapshotFile) || !this.snapshotNeedsMigration(snapshotFile)) {
                continue;
            }
            SnapshotData snapshot = this.snapshotReader.readFile(snapshotFile);
            this.snapshotWriter.writeFile(layout, snapshotFile, snapshot);
            migrated += 1;
        }
        return migrated;
    }

    private boolean snapshotNeedsMigration(Path snapshotFile) throws IOException {
        if (!this.isSnapshotContentRefVersion(snapshotFile)) {
            return true;
        }
        var metadata = this.snapshotReader.loadSectionIndex(snapshotFile);
        return metadata.sectionCount() > 0 && metadata.chunks().stream()
                .anyMatch(chunk -> chunk.contentRefs().isEmpty());
    }

    private boolean isSnapshotContentRefVersion(Path snapshotFile) throws IOException {
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

    private int repairWholeDimensionEntityCheckpoints(
            ProjectLayout layout,
            List<ProjectVersion> versions
    ) throws IOException {
        Map<String, ProjectVersion> versionsById = new LinkedHashMap<>();
        for (ProjectVersion version : versions) {
            versionsById.put(version.id(), version);
        }

        int repaired = 0;
        Set<String> processedCheckpoints = new LinkedHashSet<>();
        for (ProjectVersion version : versions) {
            String checkpointId = version.entityCheckpointId();
            if (checkpointId == null || checkpointId.isBlank() || !processedCheckpoints.add(checkpointId)) {
                continue;
            }
            Path checkpointFile = layout.entityCheckpointFile(checkpointId);
            if (!Files.exists(checkpointFile)) {
                continue;
            }

            var checkpointMetadata = this.snapshotReader.loadSectionIndex(checkpointFile);
            Set<ChunkPoint> retained = new LinkedHashSet<>();
            checkpointMetadata.chunks().stream()
                    .filter(chunk -> chunk.entityCount() > 0)
                    .forEach(chunk -> retained.add(chunk.chunk()));
            if (!this.addLineagePatchChunks(layout, version, versionsById, retained)) {
                continue;
            }
            if (checkpointMetadata.chunks().stream().allMatch(chunk -> retained.contains(chunk.chunk()))) {
                continue;
            }

            int originalChunkCount = checkpointMetadata.chunks().size();
            SnapshotData snapshot = this.snapshotReader.readFile(checkpointFile, retained);
            this.snapshotWriter.writeFile(layout, checkpointFile, snapshot);
            repaired += 1;
            LumaMod.LOGGER.warn(
                    "Repaired entity checkpoint {} scope from {} to {} chunks",
                    checkpointId,
                    originalChunkCount,
                    snapshot.chunks().size()
            );
        }
        return repaired;
    }

    private boolean addLineagePatchChunks(
            ProjectLayout layout,
            ProjectVersion target,
            Map<String, ProjectVersion> versionsById,
            Set<ChunkPoint> retained
    ) throws IOException {
        Set<String> visited = new LinkedHashSet<>();
        ProjectVersion cursor = target;
        while (cursor != null && visited.add(cursor.id())) {
            for (String patchId : cursor.patchIds() == null ? List.<String>of() : cursor.patchIds()) {
                Optional<PatchMetadata> metadata = this.patchMetaRepository.load(layout, patchId);
                if (metadata.isEmpty()) {
                    LumaMod.LOGGER.warn("Skipped entity checkpoint repair because patch metadata is missing for {}", patchId);
                    return false;
                }
                metadata.get().chunks().forEach(chunk -> retained.add(chunk.chunk()));
            }
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionsById.get(cursor.parentVersionId());
        }
        return true;
    }

    private int quarantineOrphanBaselines(ProjectLayout layout, List<ProjectVersion> versions) throws IOException {
        Set<ChunkPoint> retained = new LinkedHashSet<>();
        for (ProjectVersion version : versions) {
            for (String patchId : version.patchIds() == null ? List.<String>of() : version.patchIds()) {
                Optional<PatchMetadata> metadata = this.patchMetaRepository.load(layout, patchId);
                if (metadata.isEmpty()) {
                    LumaMod.LOGGER.warn("Skipped baseline cleanup because patch metadata is missing for {}", patchId);
                    return 0;
                }
                metadata.get().chunks().forEach(chunk -> retained.add(chunk.chunk()));
            }
        }
        this.recoveryRepository.loadDraft(layout).ifPresent(draft -> {
            retained.addAll(ChunkSelectionFactory.fromStoredChanges(draft.changes()));
            retained.addAll(ChunkSelectionFactory.fromStoredEntityChanges(draft.entityChanges()));
        });
        this.recoveryRepository.loadOperationDraft(layout).ifPresent(draft -> {
            retained.addAll(ChunkSelectionFactory.fromStoredChanges(draft.changes()));
            retained.addAll(ChunkSelectionFactory.fromStoredEntityChanges(draft.entityChanges()));
        });
        return this.baselineChunkRepository.quarantineExcept(layout, retained);
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
