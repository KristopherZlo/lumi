package io.github.luma.minecraft.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Normalizes unsafe transient block states before Lumi stores or reapplies
 * history data.
 */
public final class PersistentBlockStatePolicy {

    public PersistentBlockState normalize(BlockState state, CompoundTag blockEntityTag) {
        BlockState normalizedState = this.normalizeState(state);
        CompoundTag normalizedBlockEntity = !normalizedState.hasBlockEntity() || blockEntityTag == null
                ? null
                : blockEntityTag.copy();
        return new PersistentBlockState(normalizedState, normalizedBlockEntity);
    }

    public BlockState normalizeState(BlockState state) {
        if (state == null || this.isTransientPistonState(state)) {
            return Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    public boolean isTransientPistonState(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.is(Blocks.MOVING_PISTON);
    }

    public record PersistentBlockState(BlockState state, CompoundTag blockEntityTag) {
    }
}
