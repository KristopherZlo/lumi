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

    public SnapshotRef capture(ProjectLayout layout, String projectId, String snapshotId, java.util.Collection<ChunkPoint> chunks, ServerLevel level, Instant now) throws IOException {
        Files.createDirectories(layout.snapshotsDir());

        CompoundTag root = new CompoundTag();
        root.putString("project_id", projectId);
        root.putLong("created_at", now.toEpochMilli());
        root.putInt("min_build_height", level.getMinY());
        root.putInt("max_build_height", level.getMaxY());

        ListTag chunkTags = new ListTag();
        for (ChunkPoint chunk : chunks) {
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
                        CompoundTag blockTag = new CompoundTag();
                        blockTag.putInt("x", x);
                        blockTag.putInt("y", y);
                        blockTag.putInt("z", z);
                        blockTag.put("state", NbtUtils.writeBlockState(level.getBlockState(pos)));

                        BlockEntity blockEntity = level.getBlockEntity(pos);
                        if (blockEntity != null) {
                            blockTag.put("block_entity", blockEntity.saveWithFullMetadata(level.registryAccess()));
                        }

                        blocks.add(blockTag);
                    }
                }
            }
            chunkTag.put("blocks", blocks);
            chunkTags.add(chunkTag);
        }

        root.put("chunks", chunkTags);
        Path snapshotFile = layout.snapshotsDir().resolve(snapshotId + ".nbt.lz4");
        try (var output = new DataOutputStream(new LZ4FrameOutputStream(new BufferedOutputStream(Files.newOutputStream(snapshotFile))))) {
            NbtIo.write(root, output);
        }

        return new SnapshotRef(snapshotId, projectId, snapshotFile.getFileName().toString(), chunks.size(), Files.size(snapshotFile), now);
    }

    public void restore(ProjectLayout layout, SnapshotRef snapshot, ServerLevel level) throws IOException {
        CompoundTag root;
        Path snapshotFile = layout.snapshotsDir().resolve(snapshot.fileName());
        try (var input = new DataInputStream(new LZ4FrameInputStream(new BufferedInputStream(Files.newInputStream(snapshotFile))))) {
            root = NbtIo.read(input, NbtAccounter.unlimitedHeap());
        }

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
}
