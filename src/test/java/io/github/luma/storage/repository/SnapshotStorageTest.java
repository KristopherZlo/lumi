package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.jpountz.lz4.LZ4FrameOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotStorageTest {

    private static final int SNAPSHOT_MAGIC = 0x4C534E50;

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
        List<EntityPayload> entitySnapshots = List.of(entity(
                "minecraft:item",
                "00000000-0000-0000-0000-000000000001"
        ));

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
                        blockEntities,
                        entitySnapshots
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
        assertEquals(entitySnapshots, restoredChunk.entitySnapshots());
        assertEquals(1, restoredChunk.sections().size());
        SnapshotSectionData restoredSection = restoredChunk.sections().getFirst();
        assertEquals(0, restoredSection.sectionY());
        assertEquals(List.of(state("minecraft:stone"), state("minecraft:gold_block")), restoredSection.palette());
        assertArrayEquals(indexes, restoredSection.paletteIndexes());
    }

    @Test
    void writesPreparedChunkPayload() throws Exception {
        short[] indexes = new short[4096];
        indexes[0] = 1;
        indexes[1] = 1;
        indexes[2] = 2;

        ChunkSnapshotPayload payload = new ChunkSnapshotPayload(
                4,
                -2,
                0,
                15,
                List.of(new ChunkSectionSnapshotPayload(
                        0,
                        List.of(state("minecraft:air"), state("minecraft:stone"), state("minecraft:gold_block")),
                        packIndexes(indexes, 2),
                        2
                )),
                Map.of(SnapshotWriter.packVerticalIndex(5, 1, 0), blockEntity("minecraft:chest")),
                List.of(entity("minecraft:item", "00000000-0000-0000-0000-000000000002"))
        );

        Path file = this.tempDir.resolve("prepared.bin.lz4");
        this.writer.writePreparedChunkFile(file, "project", payload, Instant.parse("2026-04-20T10:00:00Z"));
        SnapshotData restored = this.reader.readFile(file);

        SnapshotChunkData restoredChunk = restored.chunks().getFirst();
        assertEquals(4, restoredChunk.chunkX());
        assertEquals(-2, restoredChunk.chunkZ());
        assertEquals(payload.blockEntities(), restoredChunk.blockEntities());
        assertEquals(payload.entitySnapshots(), restoredChunk.entitySnapshots());
        assertEquals(1, restoredChunk.sections().size());
        SnapshotSectionData restoredSection = restoredChunk.sections().getFirst();
        assertEquals(List.of(state("minecraft:air"), state("minecraft:stone"), state("minecraft:gold_block")), restoredSection.palette());
        assertArrayEquals(indexes, restoredSection.paletteIndexes());
    }

    @Test
    void loadsChunkListWithoutMaterializingSnapshotData() throws Exception {
        short[] indexes = new short[4096];
        SnapshotData snapshot = new SnapshotData(
                "project",
                Instant.parse("2026-04-20T10:00:00Z"),
                0,
                15,
                List.of(
                        new SnapshotChunkData(
                                2,
                                3,
                                List.of(new SnapshotSectionData(0, List.of(state("minecraft:stone")), indexes)),
                                Map.of(SnapshotWriter.packVerticalIndex(5, 1, 0), blockEntity("minecraft:chest"))
                        ),
                        new SnapshotChunkData(
                                -1,
                                4,
                                List.of(new SnapshotSectionData(0, List.of(state("minecraft:gold_block")), indexes)),
                                Map.of()
                        )
                )
        );

        Path file = this.tempDir.resolve("chunk-list.bin.lz4");
        this.writer.writeFile(file, snapshot);

        assertEquals(
                List.of(new io.github.luma.domain.model.ChunkPoint(2, 3), new io.github.luma.domain.model.ChunkPoint(-1, 4)),
                this.reader.loadChunks(file)
        );
    }

    @Test
    void readsVersionFourSnapshotsAsBlockOnly() throws Exception {
        Path file = this.tempDir.resolve("legacy-v4.bin.lz4");
        try (DataOutputStream data = new DataOutputStream(new LZ4FrameOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file))
        ))) {
            data.writeInt(SNAPSHOT_MAGIC);
            data.writeInt(4);
            data.writeUTF("project");
            data.writeLong(Instant.parse("2026-04-20T10:00:00Z").toEpochMilli());
            data.writeInt(0);
            data.writeInt(15);
            data.writeInt(1);
            data.writeInt(7);
            data.writeInt(8);
            data.writeInt(0);
            data.writeInt(0);
            data.writeInt(1);
            StorageIo.writeCompound(data, entity("minecraft:item", "00000000-0000-0000-0000-000000000003").copyTag());
        }

        SnapshotData restored = this.reader.readFile(file);

        assertEquals(1, restored.chunks().size());
        assertEquals(List.of(), restored.chunks().getFirst().entitySnapshots());
        assertEquals(List.of(new io.github.luma.domain.model.ChunkPoint(7, 8)), this.reader.loadChunks(file));
    }

    @Test
    void rejectsImpossibleSnapshotChunkCount() throws Exception {
        Path file = this.tempDir.resolve("bad-chunk-count.bin.lz4");
        try (DataOutputStream data = new DataOutputStream(new LZ4FrameOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file))
        ))) {
            data.writeInt(SNAPSHOT_MAGIC);
            data.writeInt(5);
            data.writeUTF("project");
            data.writeLong(Instant.parse("2026-04-20T10:00:00Z").toEpochMilli());
            data.writeInt(0);
            data.writeInt(15);
            data.writeInt(Integer.MAX_VALUE);
        }

        assertThrows(java.io.IOException.class, () -> this.reader.readFile(file));
    }

    @Test
    void rejectsImpossibleSnapshotPaletteIndexes() throws Exception {
        Path file = this.tempDir.resolve("bad-palette-indexes.bin.lz4");
        try (DataOutputStream data = new DataOutputStream(new LZ4FrameOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file))
        ))) {
            data.writeInt(SNAPSHOT_MAGIC);
            data.writeInt(5);
            data.writeUTF("project");
            data.writeLong(Instant.parse("2026-04-20T10:00:00Z").toEpochMilli());
            data.writeInt(0);
            data.writeInt(15);
            data.writeInt(1);
            data.writeInt(0);
            data.writeInt(0);
            data.writeInt(1);
            data.writeInt(0);
            data.writeInt(0);
            data.writeInt(1);
            StorageIo.writeCompound(data, state("minecraft:stone"));
            data.writeInt(1);
            data.writeShort(1);
        }

        assertThrows(java.io.IOException.class, () -> this.reader.readFile(file));
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

    private static EntityPayload entity(String type, String uuid) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("id", type);
        tag.putString("UUID", uuid);
        return new EntityPayload(tag);
    }

    private static long[] packIndexes(short[] indexes, int bitsPerEntry) {
        int bitCount = indexes.length * bitsPerEntry;
        long[] packed = new long[(bitCount + 63) / 64];
        long mask = (1L << bitsPerEntry) - 1L;
        for (int index = 0; index < indexes.length; index++) {
            long value = indexes[index] & mask;
            long bitIndex = (long) index * bitsPerEntry;
            int startLong = (int) (bitIndex >>> 6);
            int startOffset = (int) (bitIndex & 63L);
            packed[startLong] |= value << startOffset;
            int spill = startOffset + bitsPerEntry - 64;
            if (spill > 0) {
                packed[startLong + 1] |= value >>> (bitsPerEntry - spill);
            }
        }
        return packed;
    }
}
