package io.github.luma.client.preview;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

final class PreviewRenderableBlockFilter {

    boolean shouldRenderModel(
            BlockGetter blocks,
            BlockPos pos,
            BlockState state,
            BlockPos.MutableBlockPos neighborPos
    ) {
        if (state == null || state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }

        for (Direction direction : Direction.values()) {
            neighborPos.setWithOffset(pos, direction);
            if (Block.shouldRenderFace(state, blocks.getBlockState(neighborPos), direction)) {
                return true;
            }
        }
        return false;
    }

    boolean shouldRenderFluid(
            BlockGetter blocks,
            BlockPos pos,
            FluidState fluidState,
            BlockPos.MutableBlockPos neighborPos
    ) {
        if (fluidState == null || fluidState.isEmpty()) {
            return false;
        }

        for (Direction direction : Direction.values()) {
            neighborPos.setWithOffset(pos, direction);
            if (!blocks.getFluidState(neighborPos).is(fluidState.getType())) {
                return true;
            }
        }
        return false;
    }
}
