package io.github.luma.storage.repository;

import io.github.luma.domain.model.Bounds3i;
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

    public SnapshotRef capture(ProjectLayout layout, String projectId, String snapshotId, Bounds3i bounds, ServerLevel level, Instant now) throws IOException {
        Files.createDirectories(layout.snapshotsDir());

        CompoundTag root = new CompoundTag();
        root.putString("project_id", projectId);
        root.putLong("created_at", now.toEpochMilli());
        root.put("bounds", this.writeBounds(bounds));

        ListTag blocks = new ListTag();
        for (int x = bounds.min().x(); x <= bounds.max().x(); x++) {
            for (int y = bounds.min().y(); y <= bounds.max().y(); y++) {
                for (int z = bounds.min().z(); z <= bounds.max().z(); z++) {
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

        root.put("blocks", blocks);
        Path snapshotFile = layout.snapshotsDir().resolve(snapshotId + ".nbt.lz4");
        try (var output = new DataOutputStream(new LZ4FrameOutputStream(new BufferedOutputStream(Files.newOutputStream(snapshotFile))))) {
            NbtIo.write(root, output);
        }

        return new SnapshotRef(snapshotId, projectId, snapshotFile.getFileName().toString(), bounds, Files.size(snapshotFile), now);
    }

    public void restore(ProjectLayout layout, SnapshotRef snapshot, ServerLevel level) throws IOException {
        CompoundTag root;
        Path snapshotFile = layout.snapshotsDir().resolve(snapshot.fileName());
        try (var input = new DataInputStream(new LZ4FrameInputStream(new BufferedInputStream(Files.newInputStream(snapshotFile))))) {
            root = NbtIo.read(input, NbtAccounter.unlimitedHeap());
        }

        ListTag blocks = root.getListOrEmpty("blocks");
        var blockLookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
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

    private CompoundTag writeBounds(Bounds3i bounds) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("min_x", bounds.min().x());
        tag.putInt("min_y", bounds.min().y());
        tag.putInt("min_z", bounds.min().z());
        tag.putInt("max_x", bounds.max().x());
        tag.putInt("max_y", bounds.max().y());
        tag.putInt("max_z", bounds.max().z());
        return tag;
    }
}
