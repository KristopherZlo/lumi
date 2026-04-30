package io.github.luma.minecraft.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

final class SectionLightUpdatePlanner {

    boolean check(ServerLevel level, BlockPos pos, BlockState currentState, BlockState targetState) {
        if (level == null || pos == null || !this.requiresLightCheck(currentState, targetState)) {
            return false;
        }
        level.getLightEngine().checkBlock(pos);
        return true;
    }

    boolean requiresLightCheck(BlockState currentState, BlockState targetState) {
        if (currentState == null || targetState == null || currentState == targetState) {
            return false;
        }
        return currentState.getLightEmission() != targetState.getLightEmission()
                || currentState.getLightBlock() != targetState.getLightBlock()
                || currentState.useShapeForLightOcclusion() != targetState.useShapeForLightOcclusion()
                || currentState.propagatesSkylightDown() != targetState.propagatesSkylightDown()
                || currentState.canOcclude() != targetState.canOcclude()
                || currentState.blocksMotion() != targetState.blocksMotion()
                || !currentState.getFluidState().equals(targetState.getFluidState());
    }
}
