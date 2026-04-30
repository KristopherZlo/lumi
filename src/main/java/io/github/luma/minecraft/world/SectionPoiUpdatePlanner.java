package io.github.luma.minecraft.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

final class SectionPoiUpdatePlanner {

    void update(ServerLevel level, BlockPos pos, BlockState currentState, BlockState targetState) {
        if (level == null || pos == null || currentState == null || targetState == null) {
            return;
        }
        level.updatePOIOnBlockStateChange(pos, currentState, targetState);
    }
}
