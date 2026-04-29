package io.github.luma.minecraft.world;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class BlockPlacementUpdateDecider {

    boolean requiresUpdate(
            ServerLevel level,
            BlockPos pos,
            BlockState currentState,
            BlockState targetState,
            CompoundTag targetBlockEntityTag
    ) {
        if (!currentState.equals(targetState)) {
            return true;
        }

        if (targetBlockEntityTag == null && !currentState.hasBlockEntity()) {
            return false;
        }

        BlockEntity currentBlockEntity = level.getBlockEntity(pos);
        if (currentBlockEntity == null) {
            return targetBlockEntityTag != null;
        }
        if (targetBlockEntityTag == null) {
            return true;
        }

        CompoundTag currentBlockEntityTag = currentBlockEntity.saveWithFullMetadata(level.registryAccess());
        return !Objects.equals(currentBlockEntityTag, targetBlockEntityTag);
    }
}
