package io.github.luma.integration.axiom;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

record AxiomBlockMutation(
        BlockPos pos,
        BlockState newState,
        CompoundTag newBlockEntity
) {

    AxiomBlockMutation {
        pos = pos == null ? null : pos.immutable();
        newBlockEntity = newBlockEntity == null ? null : newBlockEntity.copy();
    }
}
