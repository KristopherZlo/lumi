package io.github.luma.minecraft.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

final class SectionLightUpdatePlanner {

    boolean plan(SectionLightUpdateBatch batch, BlockPos pos, BlockState currentState, BlockState targetState) {
        if (batch == null || pos == null || !this.requiresLightCheck(currentState, targetState)) {
            return false;
        }
        batch.add(pos);
        return true;
    }

    int apply(ServerLevel level, SectionLightUpdateBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        if (WorldLightUpdateContext.enqueue(batch)) {
            return 0;
        }
        if (level == null) {
            return 0;
        }
        for (BlockPos pos : batch.positions()) {
            level.getLightEngine().checkBlock(pos);
        }
        return batch.size();
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
