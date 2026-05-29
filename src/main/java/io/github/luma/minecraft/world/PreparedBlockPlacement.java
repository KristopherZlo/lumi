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
        NONE(false, false, false),
        FORCE_FINAL_REPLAY(true, false, false),
        SUPPRESS_POST_REPLAY_FLUID(false, true, false),
        FORCE_FINAL_REPLAY_AND_SUPPRESS_POST_REPLAY_FLUID(true, true, false),
        SUPPRESS_POST_REPLAY_MECHANISM(false, false, true),
        FORCE_FINAL_REPLAY_AND_SUPPRESS_POST_REPLAY_MECHANISM(true, false, true),
        SUPPRESS_POST_REPLAY_FLUID_AND_MECHANISM(false, true, true),
        FORCE_FINAL_REPLAY_AND_SUPPRESS_POST_REPLAY_FLUID_AND_MECHANISM(true, true, true);

        private final boolean forceFinalReplay;
        private final boolean suppressPostReplayFluid;
        private final boolean suppressPostReplayMechanism;

        ReplayHint(
                boolean forceFinalReplay,
                boolean suppressPostReplayFluid,
                boolean suppressPostReplayMechanism
        ) {
            this.forceFinalReplay = forceFinalReplay;
            this.suppressPostReplayFluid = suppressPostReplayFluid;
            this.suppressPostReplayMechanism = suppressPostReplayMechanism;
        }

        boolean forcesFinalReplay() {
            return this.forceFinalReplay;
        }

        boolean suppressesPostReplayFluid() {
            return this.suppressPostReplayFluid;
        }

        boolean suppressesPostReplayMechanism() {
            return this.suppressPostReplayMechanism;
        }

        static ReplayHint merge(ReplayHint left, ReplayHint right) {
            boolean force = (left != null && left.forcesFinalReplay())
                    || (right != null && right.forcesFinalReplay());
            boolean suppressFluid = (left != null && left.suppressesPostReplayFluid())
                    || (right != null && right.suppressesPostReplayFluid());
            boolean suppressMechanism = (left != null && left.suppressesPostReplayMechanism())
                    || (right != null && right.suppressesPostReplayMechanism());
            return of(force, suppressFluid, suppressMechanism);
        }

        static ReplayHint of(boolean forceFinalReplay, boolean suppressPostReplayFluid) {
            return of(forceFinalReplay, suppressPostReplayFluid, false);
        }

        static ReplayHint of(
                boolean forceFinalReplay,
                boolean suppressPostReplayFluid,
                boolean suppressPostReplayMechanism
        ) {
            if (forceFinalReplay && suppressPostReplayFluid && suppressPostReplayMechanism) {
                return FORCE_FINAL_REPLAY_AND_SUPPRESS_POST_REPLAY_FLUID_AND_MECHANISM;
            }
            if (forceFinalReplay && suppressPostReplayFluid) {
                return FORCE_FINAL_REPLAY_AND_SUPPRESS_POST_REPLAY_FLUID;
            }
            if (forceFinalReplay && suppressPostReplayMechanism) {
                return FORCE_FINAL_REPLAY_AND_SUPPRESS_POST_REPLAY_MECHANISM;
            }
            if (suppressPostReplayFluid && suppressPostReplayMechanism) {
                return SUPPRESS_POST_REPLAY_FLUID_AND_MECHANISM;
            }
            if (forceFinalReplay) {
                return FORCE_FINAL_REPLAY;
            }
            if (suppressPostReplayFluid) {
                return SUPPRESS_POST_REPLAY_FLUID;
            }
            if (suppressPostReplayMechanism) {
                return SUPPRESS_POST_REPLAY_MECHANISM;
            }
            return NONE;
        }
    }
}
