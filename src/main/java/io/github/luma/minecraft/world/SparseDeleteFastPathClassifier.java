package io.github.luma.minecraft.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.state.BlockState;

final class SparseDeleteFastPathClassifier {

    boolean canDelete(BlockState currentState, BlockState targetState, CompoundTag targetBlockEntityTag) {
        if (currentState == null || targetState == null || !targetState.isAir() || targetBlockEntityTag != null) {
            return false;
        }
        if (currentState.hasBlockEntity() || targetState.hasBlockEntity()) {
            return false;
        }
        return !PoiTypes.hasPoi(currentState) && !PoiTypes.hasPoi(targetState);
    }
}
