package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.storage.ProjectLayout;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
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

        assertSnapshotVersion(file, 8);
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
        assertEquals(1, restoredSection.bitsPerEntry());
        assertEquals(0, restoredSection.paletteIndexAt(0));
        assertEquals(1, restoredSection.paletteIndexAt(1));
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
                        packMinecraftIndexes(indexes, 2),
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
        assertArrayEquals(payload.sections().getFirst().packedStorage(), restoredSection.packedStorage());
        assertArrayEquals(indexes, restoredSection.paletteIndexes());
    }

    @Test
    void singlePaletteSectionRoundTripsWithoutPackedStorage() throws Exception {
        SnapshotData snapshot = new SnapshotData(
                "project",
                Instant.parse("2026-04-20T10:00:00Z"),
                0,
                15,
                List.of(new SnapshotChunkData(
                        0,
                        0,
                        List.of(new SnapshotSectionData(0, List.of(state("minecraft:stone")), 0, new long[0])),
                        Map.of()
                ))
        );

        Path file = this.tempDir.resolve("single-palette.bin.lz4");
        this.writer.writeFile(file, snapshot);
        SnapshotSectionData section = this.reader.readFile(file).chunks().getFirst().sections().getFirst();

        assertEquals(0, section.bitsPerEntry());
        assertEquals(0, section.packedStorage().length);
        assertEquals(0, section.paletteIndexAt(0));
        assertEquals(0, section.paletteIndexAt(4095));
    }

    @Test
    void multiPalettePackedSectionRoundTrips() throws Exception {
        short[] indexes = new short[4096];
        indexes[0] = 1;
        indexes[257] = 2;
        long[] packed = packMinecraftIndexes(indexes, 2);
        SnapshotData snapshot = snapshotWithSection(new SnapshotSectionData(
                0,
                List.of(state("minecraft:air"), state("minecraft:stone"), state("minecraft:gold_block")),
                2,
                packed
        ));

        Path file = this.tempDir.resolve("multi-palette-packed.bin.lz4");
        this.writer.writeFile(file, snapshot);
        SnapshotSectionData section = this.reader.readFile(file).chunks().getFirst().sections().getFirst();

        assertEquals(2, section.bitsPerEntry());
        assertArrayEquals(packed, section.packedStorage());
        assertEquals(1, section.paletteIndexAt(0));
        assertEquals(2, section.paletteIndexAt(257));
    }

    @Test
    void minecraftStylePaddedLongsRoundTrip() throws Exception {
        short[] indexes = new short[4096];
        indexes[11] = 16;
        indexes[12] = 1;
        indexes[13] = 15;
        long[] packed = packMinecraftIndexes(indexes, 5);
        SnapshotData snapshot = snapshotWithSection(new SnapshotSectionData(0, palette(17), 5, packed));

        Path file = this.tempDir.resolve("minecraft-padded.bin.lz4");
        this.writer.writeFile(file, snapshot);
        SnapshotSectionData section = this.reader.readFile(file).chunks().getFirst().sections().getFirst();

        assertArrayEquals(packed, section.packedStorage());
        assertEquals(16, section.paletteIndexAt(11));
        assertEquals(1, section.paletteIndexAt(12));
        assertEquals(15, section.paletteIndexAt(13));
    }

    @Test
    void readsSnapshotV7PaletteIndexes() throws Exception {
        short[] indexes = new short[4096];
        indexes[0] = 1;
        Path file = this.tempDir.resolve("snapshot-v7.bin.lz4");
        this.writeSnapshotFile(file, 7, this.v7Chunk(indexes));

        SnapshotSectionData section = this.reader.readFile(file).chunks().getFirst().sections().getFirst();

        assertEquals(1, section.paletteIndexAt(0));
        assertArrayEquals(indexes, section.paletteIndexes());
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
    void readsOnlySelectedChunksFromAddressableSnapshot() throws Exception {
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
                                Map.of()
                        ),
                        new SnapshotChunkData(
                                -1,
                                4,
                                List.of(new SnapshotSectionData(0, List.of(state("minecraft:gold_block")), indexes)),
                                Map.of()
                        )
                )
        );

        Path file = this.tempDir.resolve("selected-chunk.bin.lz4");
        this.writer.writeFile(file, snapshot);
        SnapshotData selected = this.reader.readFile(
                file,
                List.of(new io.github.luma.domain.model.ChunkPoint(-1, 4))
        );

        assertEquals(1, selected.chunks().size());
        assertEquals(-1, selected.chunks().getFirst().chunkX());
        assertEquals(4, selected.chunks().getFirst().chunkZ());
    }

    @Test
    void exposesAddressableSnapshotSectionIndex() throws Exception {
        short[] indexes = new short[4096];
        SnapshotData snapshot = new SnapshotData(
                "project",
                Instant.parse("2026-04-20T10:00:00Z"),
                0,
                31,
                List.of(new SnapshotChunkData(
                        2,
                        3,
                        List.of(
                                new SnapshotSectionData(0, List.of(state("minecraft:stone")), indexes),
                                new SnapshotSectionData(1, List.of(state("minecraft:gold_block")), indexes)
                        ),
                        Map.of(),
                        List.of(entity("minecraft:item", "00000000-0000-0000-0000-000000000004"))
                ))
        );

        Path file = this.tempDir.resolve("section-index.bin.lz4");
        this.writer.writeFile(file, snapshot);
        var metadata = this.reader.loadSectionIndex(file);

        assertEquals("project", metadata.projectId());
        assertEquals(1, metadata.chunks().size());
        assertEquals(2, metadata.sectionCount());
        assertEquals(1, metadata.entityCount());
        assertEquals(2, metadata.chunks().getFirst().sectionFingerprints().size());
        assertEquals(64, metadata.chunks().getFirst().sectionFingerprints().getFirst().sha256().length());
    }

    @Test
    void preparedSnapshotsWireSectionContentRefsIntoFrameIndex() throws Exception {
        short[] indexes = new short[4096];
        indexes[0] = 1;
        ChunkSectionSnapshotPayload section = new ChunkSectionSnapshotPayload(
                0,
                List.of(state("minecraft:air"), state("minecraft:stone")),
                packMinecraftIndexes(indexes, 1),
                1
        );
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("content-ref-project.mbp"));

        this.writer.writePreparedSnapshot(
                layout,
                "project",
                "snapshot-content-ref",
                List.of(
                        new ChunkSnapshotPayload(0, 0, 0, 15, List.of(section), Map.of(), List.of()),
                        new ChunkSnapshotPayload(1, 0, 0, 15, List.of(section), Map.of(), List.of())
                ),
                Instant.parse("2026-04-20T10:00:00Z")
        );

        var metadata = this.reader.loadSectionIndex(layout.snapshotFile("snapshot-content-ref"));
        assertEquals(2, metadata.sectionCount());
        assertEquals(1, Files.list(layout.contentCacheDir()).count());
        assertEquals(
                metadata.chunks().getFirst().contentRefs().getFirst().sha256(),
                metadata.chunks().get(1).contentRefs().getFirst().sha256()
        );

        SnapshotData restored = this.reader.readFile(layout.snapshotFile("snapshot-content-ref"));
        assertEquals(
                metadata.chunks().getFirst().contentRefs().getFirst().sha256(),
                restored.chunks().getFirst().sections().getFirst().contentRef().sha256()
        );
    }

    @Test
    void rejectsPreCurrentSnapshotFormats() throws Exception {
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

        assertThrows(java.io.IOException.class, () -> this.reader.readFile(file));
        assertThrows(java.io.IOException.class, () -> this.reader.loadChunks(file));
    }

    @Test
    void rejectsImpossibleSnapshotChunkCount() throws Exception {
        Path file = this.tempDir.resolve("bad-chunk-count.bin.lz4");
        try (DataOutputStream data = new DataOutputStream(Files.newOutputStream(file))) {
            data.writeInt(SNAPSHOT_MAGIC);
            data.writeInt(8);
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
        byte[] chunk = this.corruptPaletteChunk();
        byte[] compressedChunk = this.compressFrame(chunk);
        try (DataOutputStream data = new DataOutputStream(Files.newOutputStream(file))) {
            data.writeInt(SNAPSHOT_MAGIC);
            data.writeInt(7);
            data.writeUTF("project");
            data.writeLong(Instant.parse("2026-04-20T10:00:00Z").toEpochMilli());
            data.writeInt(0);
            data.writeInt(15);
            data.writeInt(1);
            data.writeInt(0);
            data.writeInt(0);
            data.writeInt(0);
            data.writeInt(0);
            data.writeInt(chunk.length);
            data.writeInt(compressedChunk.length);
            data.write(compressedChunk);
        }

        assertThrows(java.io.IOException.class, () -> this.reader.readFile(file));
    }

    @Test
    void rejectsBadPackedLength() throws Exception {
        Path file = this.tempDir.resolve("bad-packed-length.bin.lz4");
        this.writeSnapshotFile(file, 8, this.v8Chunk(
                List.of(state("minecraft:air"), state("minecraft:stone")),
                1,
                new long[] {0L}
        ));

        assertThrows(java.io.IOException.class, () -> this.reader.readFile(file));
    }

    @Test
    void rejectsBadPackedPaletteIndex() throws Exception {
        short[] indexes = new short[4096];
        indexes[0] = 2;
        Path file = this.tempDir.resolve("bad-packed-palette-index.bin.lz4");
        this.writeSnapshotFile(file, 8, this.v8Chunk(
                List.of(state("minecraft:air"), state("minecraft:stone")),
                2,
                packMinecraftIndexes(indexes, 2)
        ));

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

    private static SnapshotData snapshotWithSection(SnapshotSectionData section) {
        return new SnapshotData(
                "project",
                Instant.parse("2026-04-20T10:00:00Z"),
                0,
                15,
                List.of(new SnapshotChunkData(0, 0, List.of(section), Map.of()))
        );
    }

    private static List<net.minecraft.nbt.CompoundTag> palette(int size) {
        List<net.minecraft.nbt.CompoundTag> palette = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            palette.add(state("minecraft:test_" + index));
        }
        return palette;
    }

    private static long[] packMinecraftIndexes(short[] indexes, int bitsPerEntry) {
        int valuesPerLong = Long.SIZE / bitsPerEntry;
        long[] packed = new long[SnapshotSectionData.packedLongCount(bitsPerEntry)];
        long mask = (1L << bitsPerEntry) - 1L;
        for (int index = 0; index < indexes.length; index++) {
            long value = indexes[index] & mask;
            int storageIndex = index / valuesPerLong;
            int bitOffset = (index - storageIndex * valuesPerLong) * bitsPerEntry;
            packed[storageIndex] |= value << bitOffset;
        }
        return packed;
    }

    private static void assertSnapshotVersion(Path file, int expectedVersion) throws Exception {
        try (DataInputStream data = new DataInputStream(Files.newInputStream(file))) {
            assertEquals(SNAPSHOT_MAGIC, data.readInt());
            assertEquals(expectedVersion, data.readInt());
        }
    }

    private void writeSnapshotFile(Path file, int version, byte[] chunk) throws Exception {
        byte[] compressedChunk = this.compressFrame(chunk);
        try (DataOutputStream data = new DataOutputStream(Files.newOutputStream(file))) {
            data.writeInt(SNAPSHOT_MAGIC);
            data.writeInt(version);
            data.writeUTF("project");
            data.writeLong(Instant.parse("2026-04-20T10:00:00Z").toEpochMilli());
            data.writeInt(0);
            data.writeInt(15);
            data.writeInt(1);
            data.writeInt(0);
            data.writeInt(0);
            data.writeInt(0);
            data.writeInt(0);
            data.writeInt(chunk.length);
            data.writeInt(compressedChunk.length);
            data.write(compressedChunk);
        }
    }

    private byte[] v7Chunk(short[] indexes) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(0);
            data.writeInt(0);
            data.writeInt(1);
            data.writeInt(0);
            data.writeInt(0);
            data.writeInt(2);
            StorageIo.writeCompound(data, state("minecraft:air"));
            StorageIo.writeCompound(data, state("minecraft:stone"));
            data.writeInt(indexes.length);
            for (short index : indexes) {
                data.writeShort(index);
            }
            data.writeInt(0);
        }
        return bytes.toByteArray();
    }

    private byte[] v8Chunk(
            List<net.minecraft.nbt.CompoundTag> palette,
            int bitsPerEntry,
            long[] packedStorage
    ) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(0);
            data.writeInt(0);
            data.writeInt(1);
            data.writeInt(0);
            data.writeInt(0);
            data.writeInt(palette.size());
            for (net.minecraft.nbt.CompoundTag tag : palette) {
                StorageIo.writeCompound(data, tag);
            }
            data.writeInt(bitsPerEntry);
            data.writeInt(packedStorage.length);
            for (long packedLong : packedStorage) {
                data.writeLong(packedLong);
            }
            data.writeInt(0);
        }
        return bytes.toByteArray();
    }

    private byte[] corruptPaletteChunk() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(0);
            data.writeInt(0);
            data.writeInt(1);
            data.writeInt(0);
            data.writeInt(0);
            data.writeInt(1);
            StorageIo.writeCompound(data, state("minecraft:stone"));
            data.writeInt(1);
            data.writeShort(1);
            data.writeInt(0);
        }
        return bytes.toByteArray();
    }

    private byte[] compressFrame(byte[] frame) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream output = new LZ4FrameOutputStream(bytes)) {
            output.write(frame);
        }
        return bytes.toByteArray();
    }
}
