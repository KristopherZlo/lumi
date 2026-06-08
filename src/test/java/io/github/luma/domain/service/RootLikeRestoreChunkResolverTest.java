package io.github.luma.domain.service;

import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.SnapshotReader;
import io.github.luma.storage.repository.SnapshotWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RootLikeRestoreChunkResolverTest {

    private static final Instant NOW = Instant.parse("2026-04-28T00:00:00Z");

    private final SnapshotWriter snapshotWriter = new SnapshotWriter();
    private final RootLikeRestoreChunkResolver resolver = new RootLikeRestoreChunkResolver(
            new SnapshotReader(),
            new BaselineChunkRepository()
    );

    @Test
    void usesInitialSnapshotChunksInsteadOfBaselineChunks(@TempDir Path tempDir) throws Exception {
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        this.snapshotWriter.writeFile(layout.snapshotFile("snapshot-0001"), snapshotInChunk(new ChunkPoint(3, 1)));
        createBaselineFile(layout, new ChunkPoint(9, 9));
        ProjectVersion initial = version("v0001", "snapshot-0001", VersionKind.INITIAL);

        List<ChunkPoint> chunks = this.resolver.resolve(layout, initial);

        assertEquals(List.of(new ChunkPoint(3, 1)), chunks);
    }

    private static SnapshotData snapshotInChunk(ChunkPoint chunk) {
        short[] indexes = new short[4096];
        return new SnapshotData(
                "project",
                NOW,
                64,
                79,
                List.of(new SnapshotChunkData(
                        chunk.x(),
                        chunk.z(),
                        List.of(new SnapshotSectionData(4, List.of(state("minecraft:air")), indexes)),
                        java.util.Map.of(),
                        List.of()
                ))
        );
    }

    private static void createBaselineFile(ProjectLayout layout, ChunkPoint chunk) throws Exception {
        Path directory = layout.cacheDir().resolve("baseline-chunks");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("chunk_" + chunk.x() + "_" + chunk.z() + ".bin.lz4"), "");
    }

    private static ProjectVersion version(String id, String snapshotId, VersionKind versionKind) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                "",
                snapshotId,
                List.of(),
                versionKind,
                "tester",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                NOW
        );
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }
}
