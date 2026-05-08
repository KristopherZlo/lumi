package io.github.luma.minecraft.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

public record PreparedBlockPlacement(
        BlockPos pos,
        BlockState state,
        CompoundTag blockEntityTag,
        ReplayHint replayHint
) {

    public PreparedBlockPlacement(BlockPos pos, BlockState state, CompoundTag blockEntityTag) {
        this(pos, state, blockEntityTag, ReplayHint.NONE);
    }

    public PreparedBlockPlacement {
        replayHint = replayHint == null ? ReplayHint.NONE : replayHint;
    }

    public enum ReplayHint {
        NONE,
        FORCE_FINAL_REPLAY;

        boolean forcesFinalReplay() {
            return this == FORCE_FINAL_REPLAY;
        }
    }
}
