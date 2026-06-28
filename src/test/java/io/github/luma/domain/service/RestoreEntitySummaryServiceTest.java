package io.github.luma.domain.service;

import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RestoreEntityTypeCount;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.SnapshotWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestoreEntitySummaryServiceTest {

    @TempDir
    Path tempDir;

    private final SnapshotWriter snapshotWriter = new SnapshotWriter();
    private final RestoreEntitySummaryService service = new RestoreEntitySummaryService();

    @Test
    void summarizesEntityTypesFromCheckpoint() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("project.mbp"));
        this.snapshotWriter.writeFile(layout.entityCheckpointFile("entity-checkpoint-0001"), new SnapshotData(
                "project",
                Instant.parse("2026-06-28T00:00:00Z"),
                0,
                0,
                List.of(new SnapshotChunkData(
                        0,
                        0,
                        List.of(),
                        Map.of(),
                        List.of(
                                entity("minecraft:tnt"),
                                entity("minecraft:fireball"),
                                entity("minecraft:tnt")
                        )
                ))
        ));

        List<RestoreEntityTypeCount> counts = this.service.summarize(layout, version("entity-checkpoint-0001"));

        assertEquals(List.of(
                new RestoreEntityTypeCount("minecraft:tnt", 2),
                new RestoreEntityTypeCount("minecraft:fireball", 1)
        ), counts);
    }

    @Test
    void missingCheckpointIdHasNoEntitySummary() throws Exception {
        assertEquals(List.of(), this.service.summarize(new ProjectLayout(this.tempDir), version("")));
    }

    @Test
    void restoreScopeSummaryIncludesCurrentOnlyEntityTypes() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("project.mbp"));
        this.snapshotWriter.writeFile(layout.entityCheckpointFile("target-entities"), new SnapshotData(
                "project",
                Instant.parse("2026-06-28T00:00:00Z"),
                0,
                0,
                List.of(new SnapshotChunkData(
                        0,
                        0,
                        List.of(),
                        Map.of(),
                        List.of(entity("minecraft:block_display"))
                ))
        ));
        this.snapshotWriter.writeFile(layout.entityCheckpointFile("current-entities"), new SnapshotData(
                "project",
                Instant.parse("2026-06-28T00:00:00Z"),
                0,
                0,
                List.of(new SnapshotChunkData(
                        0,
                        0,
                        List.of(),
                        Map.of(),
                        List.of(entity("minecraft:tnt"))
                ))
        ));

        List<RestoreEntityTypeCount> counts = this.service.summarize(
                layout,
                version("v0001", "target-entities"),
                version("v0002", "current-entities"),
                null
        );

        assertEquals(List.of(
                new RestoreEntityTypeCount("minecraft:block_display", 1),
                new RestoreEntityTypeCount("minecraft:tnt", 1)
        ), counts);
    }

    @Test
    void selectedAreaSummaryCountsOnlyEntitiesInsideScope() throws Exception {
        ProjectLayout layout = this.writeScopedCheckpoint();

        List<RestoreEntityTypeCount> counts = this.service.summarize(
                layout,
                version("entity-checkpoint-0001"),
                new Bounds3i(new BlockPoint(0, 60, 0), new BlockPoint(15, 70, 15)),
                PartialRestoreMode.SELECTED_AREA,
                point -> true
        );

        assertEquals(List.of(new RestoreEntityTypeCount("minecraft:tnt", 1)), counts);
    }

    @Test
    void outsideAreaSummaryCountsOnlyEntitiesOutsideScope() throws Exception {
        ProjectLayout layout = this.writeScopedCheckpoint();

        List<RestoreEntityTypeCount> counts = this.service.summarize(
                layout,
                version("entity-checkpoint-0001"),
                new Bounds3i(new BlockPoint(0, 60, 0), new BlockPoint(15, 70, 15)),
                PartialRestoreMode.OUTSIDE_SELECTED_AREA,
                point -> true
        );

        assertEquals(List.of(new RestoreEntityTypeCount("minecraft:fireball", 1)), counts);
    }

    private ProjectLayout writeScopedCheckpoint() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("scoped-project.mbp"));
        this.snapshotWriter.writeFile(layout.entityCheckpointFile("entity-checkpoint-0001"), new SnapshotData(
                "project",
                Instant.parse("2026-06-28T00:00:00Z"),
                0,
                0,
                List.of(new SnapshotChunkData(
                        0,
                        0,
                        List.of(),
                        Map.of(),
                        List.of(
                                entity("minecraft:tnt", 1.0D, 64.0D, 1.0D),
                                entity("minecraft:fireball", 32.0D, 64.0D, 1.0D)
                        )
                ))
        ));
        return layout;
    }

    private static ProjectVersion version(String entityCheckpointId) {
        return version("v0001", entityCheckpointId);
    }

    private static ProjectVersion version(String id, String entityCheckpointId) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                "",
                "",
                entityCheckpointId,
                List.of(),
                VersionKind.MANUAL,
                "tester",
                "Version",
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                Instant.parse("2026-06-28T00:00:00Z")
        );
    }

    private static EntityPayload entity(String entityType) {
        return entity(entityType, 0.0D, 64.0D, 0.0D);
    }

    private static EntityPayload entity(String entityType, double x, double y, double z) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", entityType);
        tag.putString("UUID", java.util.UUID.randomUUID().toString());
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }
}
