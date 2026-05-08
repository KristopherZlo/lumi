package io.github.luma.minecraft.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Selects placements that still need an exact final replay after vanilla
 * neighbor callbacks have drained.
 */
final class ExactReplayTargetPolicy {

    private final ExactReplayGuardBlockPolicy guardBlockPolicy = new ExactReplayGuardBlockPolicy();
    private final ConnectedBlockPlacementExpander connectedBlockPlacementExpander = new ConnectedBlockPlacementExpander();
    private final PistonMechanismPlacementExpander pistonMechanismPlacementExpander = new PistonMechanismPlacementExpander();

    boolean requiresFinalReplay(PreparedBlockPlacement placement) {
        if (placement == null || placement.pos() == null || placement.state() == null) {
            return false;
        }
        if (placement.replayHint().forcesFinalReplay()) {
            return true;
        }
        return this.requiresFinalReplay(placement.state(), placement.blockEntityTag());
    }

    boolean requiresFinalReplay(BlockState state, CompoundTag blockEntityTag) {
        if (state == null) {
            return false;
        }
        return blockEntityTag != null
                || state.hasBlockEntity()
                || this.guardBlockPolicy.shouldGuard(state)
                || this.guardBlockPolicy.shouldSuppressCallbacks(state)
                || this.connectedBlockPlacementExpander.requiresCompanion(state)
                || this.pistonMechanismPlacementExpander.requiresCompanion(state);
    }

    boolean requiresPostReplayGuard(PreparedBlockPlacement placement) {
        return placement != null
                && placement.state() != null
                && this.requiresPostReplayGuard(placement.state());
    }

    boolean requiresPostReplayGuard(BlockState state) {
        return state != null && this.guardBlockPolicy.shouldSuppressCallbacks(state);
    }
}
