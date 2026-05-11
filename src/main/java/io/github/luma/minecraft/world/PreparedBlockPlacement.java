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
        NONE(false, false),
        FORCE_FINAL_REPLAY(true, false),
        SUPPRESS_POST_REPLAY_FLUID(false, true),
        FORCE_FINAL_REPLAY_AND_SUPPRESS_POST_REPLAY_FLUID(true, true);

        private final boolean forceFinalReplay;
        private final boolean suppressPostReplayFluid;

        ReplayHint(boolean forceFinalReplay, boolean suppressPostReplayFluid) {
            this.forceFinalReplay = forceFinalReplay;
            this.suppressPostReplayFluid = suppressPostReplayFluid;
        }

        boolean forcesFinalReplay() {
            return this.forceFinalReplay;
        }

        boolean suppressesPostReplayFluid() {
            return this.suppressPostReplayFluid;
        }

        static ReplayHint merge(ReplayHint left, ReplayHint right) {
            boolean force = (left != null && left.forcesFinalReplay())
                    || (right != null && right.forcesFinalReplay());
            boolean suppressFluid = (left != null && left.suppressesPostReplayFluid())
                    || (right != null && right.suppressesPostReplayFluid());
            return of(force, suppressFluid);
        }

        static ReplayHint of(boolean forceFinalReplay, boolean suppressPostReplayFluid) {
            if (forceFinalReplay && suppressPostReplayFluid) {
                return FORCE_FINAL_REPLAY_AND_SUPPRESS_POST_REPLAY_FLUID;
            }
            if (forceFinalReplay) {
                return FORCE_FINAL_REPLAY;
            }
            if (suppressPostReplayFluid) {
                return SUPPRESS_POST_REPLAY_FLUID;
            }
            return NONE;
        }
    }
}
