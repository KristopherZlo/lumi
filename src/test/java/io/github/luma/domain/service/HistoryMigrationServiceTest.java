package io.github.luma.domain.service;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.SnapshotReader;
import io.github.luma.storage.repository.SnapshotWriter;
import io.github.luma.storage.repository.VersionRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HistoryMigrationServiceTest {

    @TempDir
    Path tempDir;

    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final SnapshotWriter snapshotWriter = new SnapshotWriter();
    private final SnapshotReader snapshotReader = new SnapshotReader();
    private final HistoryMigrationService migrationService = new HistoryMigrationService();

    @Test
    void migratesUnindexedPatchMetadataAndSnapshotContentRefsOnce() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("tower.mbp"));
        Instant now = Instant.parse("2026-04-20T10:00:00Z");
        BuildProject project = BuildProject.createWorldWorkspace("Tower", "minecraft:overworld", now)
                .withSchemaVersion(3);
        this.projectRepository.save(layout, project);

        PatchMetadata metadata = this.patchDataRepository.writePayload(
                layout,
                "patch-0001",
                project.id().toString(),
                "v0002",
                List.of(new StoredBlockChange(
                        new io.github.luma.domain.model.BlockPoint(1, 64, 1),
                        payload("minecraft:stone"),
                        payload("minecraft:gold_block")
                ))
        );
        this.patchMetaRepository.save(layout, new PatchMetadata(
                metadata.id(),
                metadata.projectId(),
                metadata.versionId(),
                metadata.dataFileName(),
                List.of(),
                metadata.stats()
        ));

        this.snapshotWriter.writeFile(layout.snapshotFile("snapshot-0001"), new SnapshotData(
                project.id().toString(),
                now,
                64,
                79,
                List.of(new SnapshotChunkData(
                        0,
                        0,
                        List.of(new SnapshotSectionData(4, List.of(state("minecraft:stone")), new short[4096])),
                        Map.of()
                ))
        ));
        this.versionRepository.save(layout, new ProjectVersion(
                "v0002",
                project.id().toString(),
                "main",
                "v0001",
                "snapshot-0001",
                List.of("patch-0001"),
                VersionKind.MANUAL,
                "tester",
                "Save",
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                now
        ));

        HistoryMigrationService.MigrationReport report = this.migrationService.migrate(layout, project);

        assertEquals(1, report.patchCount());
        assertEquals(1, report.snapshotCount());
        assertEquals(BuildProject.CURRENT_SCHEMA_VERSION, this.projectRepository.load(layout).orElseThrow().schemaVersion());
        PatchMetadata migratedPatch = this.patchMetaRepository.load(layout, "patch-0001").orElseThrow();
        assertFalse(migratedPatch.chunks().getFirst().sectionFingerprints().isEmpty());
        assertFalse(this.snapshotReader.loadSectionIndex(layout.snapshotFile("snapshot-0001"))
                .chunks()
                .getFirst()
                .contentRefs()
                .isEmpty());

        HistoryMigrationService.MigrationReport second = this.migrationService.migrate(
                layout,
                this.projectRepository.load(layout).orElseThrow()
        );
        assertEquals(0, second.patchCount());
        assertEquals(0, second.snapshotCount());
    }

    private static StatePayload payload(String blockId) {
        return new StatePayload(state(blockId), null);
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }
}
