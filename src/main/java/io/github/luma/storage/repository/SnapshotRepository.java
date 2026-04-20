package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.SnapshotRef;
import io.github.luma.storage.ProjectLayout;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class SnapshotRepository {

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
        Files.createDirectories(layout.snapshotsDir());
        Path snapshotFile = layout.snapshotsDir().resolve(snapshotId + ".nbt.lz4");
        this.captureFile(snapshotFile, projectId, chunks, level, now, overrides);

        return new SnapshotRef(snapshotId, projectId, snapshotFile.getFileName().toString(), chunks.size(), Files.size(snapshotFile), now);
    }

    public void captureFile(
            Path snapshotFile,
            String projectId,
            Collection<ChunkPoint> chunks,
            ServerLevel level,
            Instant now,
            Map<BlockPos, SnapshotBlockState> overrides
    ) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putString("project_id", projectId);
        root.putLong("created_at", now.toEpochMilli());
        root.putInt("min_build_height", level.getMinY());
        root.putInt("max_build_height", level.getMaxY());

        ListTag chunkTags = new ListTag();
        for (ChunkPoint chunk : chunks) {
            chunkTags.add(this.serializeChunk(chunk, level, overrides));
        }

        root.put("chunks", chunkTags);
        Files.createDirectories(snapshotFile.getParent());
        this.writeSnapshot(snapshotFile, root);
    }

    public void restore(ProjectLayout layout, SnapshotRef snapshot, ServerLevel level) throws IOException {
        Path snapshotFile = layout.snapshotsDir().resolve(snapshot.fileName());
        this.restoreFile(snapshotFile, level);
    }

    public void restoreFile(Path snapshotFile, ServerLevel level) throws IOException {
        this.applySnapshot(this.readSnapshot(snapshotFile), level);
    }

    public List<ChunkPoint> loadChunks(ProjectLayout layout, SnapshotRef snapshot) throws IOException {
        return this.loadChunks(layout.snapshotsDir().resolve(snapshot.fileName()));
    }

    public List<ChunkPoint> loadChunks(Path snapshotFile) throws IOException {
        CompoundTag root = this.readSnapshot(snapshotFile);
        List<ChunkPoint> chunks = new ArrayList<>();
        for (Tag chunkEntry : root.getListOrEmpty("chunks")) {
            CompoundTag chunkTag = (CompoundTag) chunkEntry;
            chunks.add(new ChunkPoint(chunkTag.getIntOr("chunk_x", 0), chunkTag.getIntOr("chunk_z", 0)));
        }
        return chunks;
    }

    private void writeSnapshot(Path snapshotFile, CompoundTag root) throws IOException {
        try (var output = new DataOutputStream(new LZ4FrameOutputStream(new BufferedOutputStream(Files.newOutputStream(snapshotFile))))) {
            NbtIo.write(root, output);
        }
    }

    private CompoundTag readSnapshot(Path snapshotFile) throws IOException {
        try (var input = new DataInputStream(new LZ4FrameInputStream(new BufferedInputStream(Files.newInputStream(snapshotFile))))) {
            return NbtIo.read(input, NbtAccounter.unlimitedHeap());
        }
    }

    private void applySnapshot(CompoundTag root, ServerLevel level) {
        ListTag chunkTags = root.getListOrEmpty("chunks");
        var blockLookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        for (Tag chunkEntry : chunkTags) {
            CompoundTag chunkTag = (CompoundTag) chunkEntry;
            ListTag blocks = chunkTag.getListOrEmpty("blocks");
            for (Tag entry : blocks) {
                CompoundTag blockTag = (CompoundTag) entry;
                BlockPos pos = new BlockPos(blockTag.getIntOr("x", 0), blockTag.getIntOr("y", 0), blockTag.getIntOr("z", 0));
                var state = NbtUtils.readBlockState(blockLookup, blockTag.getCompoundOrEmpty("state"));

                level.removeBlockEntity(pos);
                level.setBlock(pos, state, 3);

                if (blockTag.contains("block_entity")) {
                    BlockEntity blockEntity = BlockEntity.loadStatic(pos, state, blockTag.getCompoundOrEmpty("block_entity"), level.registryAccess());
                    if (blockEntity != null) {
                        level.setBlockEntity(blockEntity);
                    }
                }
            }
        }
    }

    private CompoundTag serializeChunk(ChunkPoint chunk, ServerLevel level, Map<BlockPos, SnapshotBlockState> overrides) {
        CompoundTag chunkTag = new CompoundTag();
        chunkTag.putInt("chunk_x", chunk.x());
        chunkTag.putInt("chunk_z", chunk.z());

        ListTag blocks = new ListTag();
        int minX = chunk.x() << 4;
        int maxX = minX + 15;
        int minZ = chunk.z() << 4;
        int maxZ = minZ + 15;
        for (int x = minX; x <= maxX; x++) {
            for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    SnapshotBlockState override = overrides.get(pos);
                    var state = override == null ? level.getBlockState(pos) : override.state();
                    CompoundTag blockTag = new CompoundTag();
                    blockTag.putInt("x", x);
                    blockTag.putInt("y", y);
                    blockTag.putInt("z", z);
                    blockTag.put("state", NbtUtils.writeBlockState(state));

                    CompoundTag blockEntityTag = null;
                    if (override != null) {
                        blockEntityTag = override.blockEntityTag();
                    } else {
                        BlockEntity blockEntity = level.getBlockEntity(pos);
                        if (blockEntity != null) {
                            blockEntityTag = blockEntity.saveWithFullMetadata(level.registryAccess());
                        }
                    }

                    if (blockEntityTag != null) {
                        blockTag.put("block_entity", blockEntityTag.copy());
                    }
                    blocks.add(blockTag);
                }
            }
        }
        chunkTag.put("blocks", blocks);
        return chunkTag;
    }

    public record SnapshotBlockState(net.minecraft.world.level.block.state.BlockState state, CompoundTag blockEntityTag) {
    }
}
