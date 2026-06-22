package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class BlockEntitySnapshot {

    private BlockEntitySnapshot() {
    }

    public static CompoundTag capture(ServerLevel level, BlockEntity blockEntity) {
        if (level == null || blockEntity == null) {
            return null;
        }
        try {
            return blockEntity.saveWithFullMetadata(level.registryAccess());
        } catch (RuntimeException exception) {
            LumaMod.LOGGER.warn("Skipped unsafe block entity snapshot at {}", blockEntity.getBlockPos(), exception);
            return null;
        }
    }
}
