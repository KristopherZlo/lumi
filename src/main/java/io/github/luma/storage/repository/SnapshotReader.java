package io.github.luma.storage.repository;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotRef;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.minecraft.world.PreparedBlockPlacement;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.storage.ProjectLayout;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class SnapshotReader {

    private static final int MAGIC = 0x4C534E50;
    private static final int VERSION = 4;
    private static final net.minecraft.nbt.CompoundTag AIR_TAG = createAirTag();

    public SnapshotData load(ProjectLayout layout, SnapshotRef snapshot) throws IOException {
        return this.readFile(layout.snapshotFile(snapshot.id()));
    }

    public SnapshotData readFile(Path snapshotFile) throws IOException {
        try (DataInputStream input = new DataInputStream(new LZ4FrameInputStream(
                new BufferedInputStream(Files.newInputStream(snapshotFile))
        ))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || (version != 3 && version != VERSION)) {
                throw new IOException("Unsupported snapshot format: " + snapshotFile.getFileName());
            }

            String projectId = input.readUTF();
            Instant createdAt = Instant.ofEpochMilli(input.readLong());
            int minY = input.readInt();
            int maxY = input.readInt();
            int chunkCount = input.readInt();

            List<SnapshotChunkData> chunks = new ArrayList<>();
            for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                int chunkX = input.readInt();
                int chunkZ = input.readInt();
                int sectionCount = input.readInt();
                int blockEntityCount = input.readInt();

                List<SnapshotSectionData> sections = new ArrayList<>();
                for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                    int sectionY = input.readInt();
                    int paletteSize = input.readInt();
                    List<net.minecraft.nbt.CompoundTag> palette = new ArrayList<>();
                    for (int paletteIndex = 0; paletteIndex < paletteSize; paletteIndex++) {
                        palette.add(StorageIo.readCompound(input));
                    }
                    int paletteIndexCount = input.readInt();
                    short[] indexes = new short[paletteIndexCount];
                    for (int paletteIndex = 0; paletteIndex < paletteIndexCount; paletteIndex++) {
                        indexes[paletteIndex] = input.readShort();
                    }
                    sections.add(new SnapshotSectionData(sectionY, palette, indexes));
                }

                Map<Integer, net.minecraft.nbt.CompoundTag> blockEntities = new LinkedHashMap<>();
                for (int blockEntityIndex = 0; blockEntityIndex < blockEntityCount; blockEntityIndex++) {
                    blockEntities.put(input.readInt(), StorageIo.readCompound(input));
                }
                if (version >= 4) {
                    int entityCount = input.readInt();
                    for (int entityIndex = 0; entityIndex < entityCount; entityIndex++) {
                        StorageIo.readCompound(input);
                    }
                }

                chunks.add(new SnapshotChunkData(chunkX, chunkZ, sections, blockEntities));
                BackgroundThrottle.pauseEvery(chunkIndex + 1, 4, 300_000L);
            }

            LumaMod.LOGGER.info("Loaded snapshot {} with {} chunks", snapshotFile.getFileName(), chunks.size());
            return new SnapshotData(projectId, createdAt, minY, maxY, chunks);
        }
    }

    public List<ChunkPoint> loadChunks(ProjectLayout layout, SnapshotRef snapshot) throws IOException {
        return this.loadChunks(layout.snapshotFile(snapshot.id()));
    }

    public List<ChunkPoint> loadChunks(Path snapshotFile) throws IOException {
        List<ChunkPoint> chunks = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(new LZ4FrameInputStream(
                new BufferedInputStream(Files.newInputStream(snapshotFile))
        ))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || (version != 3 && version != VERSION)) {
                throw new IOException("Unsupported snapshot format: " + snapshotFile.getFileName());
            }

            input.readUTF();
            input.readLong();
            input.readInt();
            input.readInt();
            int chunkCount = input.readInt();

            for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                int chunkX = input.readInt();
                int chunkZ = input.readInt();
                chunks.add(new ChunkPoint(chunkX, chunkZ));

                int sectionCount = input.readInt();
                int blockEntityCount = input.readInt();
                for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                    this.skipSection(input);
                }
                for (int blockEntityIndex = 0; blockEntityIndex < blockEntityCount; blockEntityIndex++) {
                    input.readInt();
                    skipCompound(input);
                }
                if (version >= 4) {
                    int entityCount = input.readInt();
                    for (int entityIndex = 0; entityIndex < entityCount; entityIndex++) {
                        skipCompound(input);
                    }
                }
                BackgroundThrottle.pauseEvery(chunkIndex + 1, 16, 150_000L);
            }
        }
        return List.copyOf(chunks);
    }

    public List<PreparedChunkBatch> decodeBatches(Path snapshotFile, ServerLevel level) throws IOException {
        return this.decodeBatches(this.readFile(snapshotFile), level);
    }

    public List<PreparedChunkBatch> decodeBatches(SnapshotData snapshot, ServerLevel level) throws IOException {
        List<PreparedChunkBatch> batches = new ArrayList<>();
        for (SnapshotChunkData chunk : snapshot.chunks()) {
            batches.add(this.decodeChunk(snapshot, chunk, level));
        }
        return batches;
    }

    private PreparedChunkBatch decodeChunk(SnapshotData snapshot, SnapshotChunkData chunk, ServerLevel level) throws IOException {
        Map<Integer, SnapshotSectionData> sections = new HashMap<>();
        for (SnapshotSectionData section : chunk.sections()) {
            sections.put(section.sectionY(), section);
        }

        List<PreparedBlockPlacement> placements = new ArrayList<>();
        int minSection = Math.floorDiv(snapshot.minBuildHeight(), 16);
        int maxSection = Math.floorDiv(snapshot.maxBuildHeight(), 16);
        for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
            SnapshotSectionData section = sections.get(sectionY);
            int sectionBaseY = sectionY << 4;
            int minY = Math.max(snapshot.minBuildHeight(), sectionBaseY);
            int maxY = Math.min(snapshot.maxBuildHeight(), sectionBaseY + 15);
            if (minY > maxY) {
                continue;
            }

            for (int y = minY; y <= maxY; y++) {
                int localY = y - sectionBaseY;
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        int stateIndex = section == null
                                ? 0
                                : section.paletteIndexes()[(localY << 8) | (localZ << 4) | localX];
                        net.minecraft.nbt.CompoundTag stateTag = section == null
                                ? AIR_TAG
                                : section.palette().get(stateIndex);
                        BlockPos pos = new BlockPos((chunk.chunkX() << 4) + localX, y, (chunk.chunkZ() << 4) + localZ);
                        placements.add(new PreparedBlockPlacement(
                                pos,
                                io.github.luma.minecraft.world.BlockStateNbtCodec.deserializeBlockState(level, stateTag),
                                readBlockEntity(chunk, snapshot.minBuildHeight(), y, localX, localZ)
                        ));
                    }
                }
            }
        }
        return new PreparedChunkBatch(new ChunkPoint(chunk.chunkX(), chunk.chunkZ()), placements);
    }

    private net.minecraft.nbt.CompoundTag readBlockEntity(SnapshotChunkData chunk, int minBuildHeight, int y, int localX, int localZ) {
        net.minecraft.nbt.CompoundTag tag = chunk.blockEntities().get(
                SnapshotWriter.packVerticalIndex(y - minBuildHeight, localX, localZ)
        );
        return tag == null ? null : tag.copy();
    }

    private void skipSection(DataInputStream input) throws IOException {
        input.readInt();
        int paletteSize = input.readInt();
        for (int paletteIndex = 0; paletteIndex < paletteSize; paletteIndex++) {
            skipCompound(input);
        }
        int paletteIndexCount = input.readInt();
        input.skipNBytes((long) paletteIndexCount * Short.BYTES);
    }

    private static void skipCompound(DataInputStream input) throws IOException {
        int length = input.readInt();
        input.skipNBytes(length);
    }

    private static net.minecraft.nbt.CompoundTag createAirTag() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("Name", "minecraft:air");
        return tag;
    }
}
