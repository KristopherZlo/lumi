package io.github.luma.minecraft.world;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * Classifies dry blocks that vanilla water can displace during fluid spread.
 */
final class FluidSensitiveBlockReplayPolicy {

    boolean requiresFluidReplayGuard(BlockState state) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        return state.canBeReplaced(Fluids.WATER)
                || state.canBeReplaced(Fluids.FLOWING_WATER)
                || isFluidSpreadDropException(state);
    }

    private boolean isFluidSpreadDropException(BlockState state) {
        return state.is(Blocks.COBWEB) || state.is(Blocks.BAMBOO_SAPLING);
    }
}
