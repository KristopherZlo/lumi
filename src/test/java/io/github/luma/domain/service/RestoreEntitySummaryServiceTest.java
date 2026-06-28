package io.github.luma.domain.service;

import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.ExternalSourceInfo;
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

    private static ProjectVersion version(String entityCheckpointId) {
        return new ProjectVersion(
                "v0001",
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
        CompoundTag tag = new CompoundTag();
        tag.putString("id", entityType);
        tag.putString("UUID", java.util.UUID.randomUUID().toString());
        return new EntityPayload(tag);
    }
}
