package io.github.luma.storage.repository;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotRef;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.storage.ProjectLayout;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class SnapshotWriter {

    private static final int MAGIC = 0x4C534E50;
    private static final int VERSION = 3;
    private static final String AIR_ID = "minecraft:air";

    public SnapshotRef capture(
            ProjectLayout layout,
            String projectId,
            String snapshotId,
            Collection<ChunkPoint> chunks,
            ServerLevel level,
            Instant now
    ) throws IOException {
        return this.capture(layout, projectId, snapshotId, chunks, level, now, Map.of());
    }

    public SnapshotRef capture(
            ProjectLayout layout,
            String projectId,
            String snapshotId,
            Collection<ChunkPoint> chunks,
            ServerLevel level,
            Instant now,
            Map<BlockPos, SnapshotBlockState> overrides
    ) throws IOException {
        SnapshotData snapshot = this.captureData(projectId, chunks, level, now, overrides);
        Path snapshotFile = layout.snapshotFile(snapshotId);
        this.writeFile(snapshotFile, snapshot);
        return new SnapshotRef(
                snapshotId,
                projectId,
                snapshotFile.getFileName().toString(),
                snapshot.chunks().size(),
                Files.size(snapshotFile),
                now
        );
    }

    public SnapshotData captureData(
            String projectId,
            Collection<ChunkPoint> chunks,
            ServerLevel level,
            Instant now,
            Map<BlockPos, SnapshotBlockState> overrides
    ) {
        List<SnapshotChunkData> chunkData = new ArrayList<>();
        int index = 0;
        for (ChunkPoint chunk : new LinkedHashSet<>(chunks)) {
            chunkData.add(this.captureChunk(chunk, level, overrides));
            index += 1;
            BackgroundThrottle.pauseEvery(index, 4, 300_000L);
        }
        LumaMod.LOGGER.info("Captured snapshot data for project {} across {} chunks", projectId, chunkData.size());
        return new SnapshotData(projectId, now, level.getMinY(), level.getMaxY(), chunkData);
    }

    public void writeFile(Path snapshotFile, SnapshotData snapshot) throws IOException {
        StorageIo.writeAtomically(snapshotFile, output -> this.writeCompressed(output, snapshot));
    }

    private void writeCompressed(OutputStream output, SnapshotData snapshot) throws IOException {
        try (DataOutputStream data = new DataOutputStream(new LZ4FrameOutputStream(new BufferedOutputStream(output)))) {
            data.writeInt(MAGIC);
            data.writeInt(VERSION);
            data.writeUTF(snapshot.projectId());
            data.writeLong(snapshot.createdAt().toEpochMilli());
            data.writeInt(snapshot.minBuildHeight());
            data.writeInt(snapshot.maxBuildHeight());
            data.writeInt(snapshot.chunks().size());

            for (SnapshotChunkData chunk : snapshot.chunks()) {
                data.writeInt(chunk.chunkX());
                data.writeInt(chunk.chunkZ());
                data.writeInt(chunk.sections().size());
                data.writeInt(chunk.blockEntities().size());

                for (SnapshotSectionData section : chunk.sections()) {
                    data.writeInt(section.sectionY());
                    data.writeInt(section.palette().size());
                    for (var tag : section.palette()) {
                        StorageIo.writeCompound(data, tag);
                    }
                    data.writeInt(section.paletteIndexes().length);
                    for (short paletteIndex : section.paletteIndexes()) {
                        data.writeShort(paletteIndex);
                    }
                }

                for (Map.Entry<Integer, net.minecraft.nbt.CompoundTag> entry : chunk.blockEntities().entrySet()) {
                    data.writeInt(entry.getKey());
                    StorageIo.writeCompound(data, entry.getValue());
                }
            }
        }
    }

    private SnapshotChunkData captureChunk(
            ChunkPoint chunk,
            ServerLevel level,
            Map<BlockPos, SnapshotBlockState> overrides
    ) {
        List<SnapshotSectionData> sections = new ArrayList<>();
        Map<Integer, net.minecraft.nbt.CompoundTag> blockEntities = new LinkedHashMap<>();
        int minSection = Math.floorDiv(level.getMinY(), 16);
        int maxSection = Math.floorDiv(level.getMaxY(), 16);

        for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
            SnapshotSectionData section = this.captureSection(chunk, sectionY, level, overrides, blockEntities);
            if (section != null) {
                sections.add(section);
            }
        }

        return new SnapshotChunkData(chunk.x(), chunk.z(), sections, blockEntities);
    }

    private SnapshotSectionData captureSection(
            ChunkPoint chunk,
            int sectionY,
            ServerLevel level,
            Map<BlockPos, SnapshotBlockState> overrides,
            Map<Integer, net.minecraft.nbt.CompoundTag> blockEntities
    ) {
        int sectionBaseY = sectionY << 4;
        int minY = Math.max(level.getMinY(), sectionBaseY);
        int maxY = Math.min(level.getMaxY(), sectionBaseY + 15);
        if (minY > maxY) {
            return null;
        }

        LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> paletteMap = new LinkedHashMap<>();
        short[] indexes = new short[4096];
        boolean nonAir = false;

        for (int y = minY; y <= maxY; y++) {
            int localY = y - sectionBaseY;
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    int worldX = (chunk.x() << 4) + localX;
                    int worldZ = (chunk.z() << 4) + localZ;
                    BlockPos pos = new BlockPos(worldX, y, worldZ);
                    SnapshotBlockState override = overrides.get(pos);
                    BlockState state = override == null ? level.getBlockState(pos) : override.state();
                    net.minecraft.nbt.CompoundTag stateTag = net.minecraft.nbt.NbtUtils.writeBlockState(state);
                    int paletteId = paletteMap.computeIfAbsent(stateTag.copy(), ignored -> paletteMap.size());
                    int localIndex = localIndex(localX, localY, localZ);
                    indexes[localIndex] = (short) paletteId;
                    if (!AIR_ID.equals(stateTag.getString("Name"))) {
                        nonAir = true;
                    }

                    net.minecraft.nbt.CompoundTag blockEntityTag = null;
                    if (override != null) {
                        blockEntityTag = override.blockEntityTag();
                    } else {
                        BlockEntity blockEntity = level.getBlockEntity(pos);
                        if (blockEntity != null) {
                            blockEntityTag = blockEntity.saveWithFullMetadata(level.registryAccess());
                        }
                    }
                    if (blockEntityTag != null) {
                        blockEntities.put(packVerticalIndex(y - level.getMinY(), localX, localZ), blockEntityTag.copy());
                    }
                }
            }
        }

        if (!nonAir) {
            return null;
        }
        return new SnapshotSectionData(sectionY, List.copyOf(paletteMap.keySet()), indexes);
    }

    private static int localIndex(int localX, int localY, int localZ) {
        return (localY << 8) | (localZ << 4) | localX;
    }

    public static int packVerticalIndex(int relativeY, int localX, int localZ) {
        return (relativeY << 8) | (localZ << 4) | localX;
    }

    public record SnapshotBlockState(BlockState state, net.minecraft.nbt.CompoundTag blockEntityTag) {
    }
}
