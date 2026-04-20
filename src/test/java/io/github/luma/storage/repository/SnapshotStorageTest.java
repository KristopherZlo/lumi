package io.github.luma.storage.repository;

import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class SnapshotStorageTest {

    @TempDir
    Path tempDir;

    private final SnapshotWriter writer = new SnapshotWriter();
    private final SnapshotReader reader = new SnapshotReader();

    @Test
    void roundTripsSparseSnapshotData() throws Exception {
        short[] indexes = new short[4096];
        indexes[0] = 0;
        indexes[1] = 1;

        LinkedHashMap<Integer, net.minecraft.nbt.CompoundTag> blockEntities = new LinkedHashMap<>();
        blockEntities.put(SnapshotWriter.packVerticalIndex(5, 1, 0), blockEntity("minecraft:chest"));

        SnapshotData snapshot = new SnapshotData(
                "project",
                Instant.parse("2026-04-20T10:00:00Z"),
                0,
                15,
                List.of(new SnapshotChunkData(
                        2,
                        3,
                        List.of(new SnapshotSectionData(
                                0,
                                List.of(state("minecraft:stone"), state("minecraft:gold_block")),
                                indexes
                        )),
                        blockEntities
                ))
        );

        Path file = this.tempDir.resolve("snapshot.bin.lz4");
        this.writer.writeFile(file, snapshot);
        SnapshotData restored = this.reader.readFile(file);

        assertEquals(snapshot.projectId(), restored.projectId());
        assertEquals(snapshot.minBuildHeight(), restored.minBuildHeight());
        assertEquals(snapshot.maxBuildHeight(), restored.maxBuildHeight());
        assertEquals(snapshot.chunks().size(), restored.chunks().size());
        SnapshotChunkData restoredChunk = restored.chunks().getFirst();
        assertEquals(2, restoredChunk.chunkX());
        assertEquals(3, restoredChunk.chunkZ());
        assertEquals(blockEntities, restoredChunk.blockEntities());
        assertEquals(1, restoredChunk.sections().size());
        SnapshotSectionData restoredSection = restoredChunk.sections().getFirst();
        assertEquals(0, restoredSection.sectionY());
        assertEquals(List.of(state("minecraft:stone"), state("minecraft:gold_block")), restoredSection.palette());
        assertArrayEquals(indexes, restoredSection.paletteIndexes());
    }

    private static net.minecraft.nbt.CompoundTag state(String blockId) {
        net.minecraft.nbt.CompoundTag state = new net.minecraft.nbt.CompoundTag();
        state.putString("Name", blockId);
        return state;
    }

    private static net.minecraft.nbt.CompoundTag blockEntity(String id) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("id", id);
        return tag;
    }
}
