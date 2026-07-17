package io.github.lumi.client.preview;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Avoids emitting model or fluid geometry whose six faces are fully hidden. */
final class PreviewRenderableBlockFilter {
    boolean shouldRenderModel(
            BlockGetter blocks,
            BlockPos pos,
            BlockState state,
            BlockPos.MutableBlockPos neighbor) {
        if (state == null || state.isAir()
                || state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            neighbor.setWithOffset(pos, direction);
            if (Block.shouldRenderFace(
                    state, blocks.getBlockState(neighbor), direction)) {
                return true;
            }
        }
        return false;
    }

    boolean shouldRenderFluid(
            BlockGetter blocks,
            BlockPos pos,
            FluidState fluid,
            BlockPos.MutableBlockPos neighbor) {
        if (fluid == null || fluid.isEmpty()) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            neighbor.setWithOffset(pos, direction);
            if (!blocks.getFluidState(neighbor).is(fluid.getType())) {
                return true;
            }
        }
        return false;
    }
}
