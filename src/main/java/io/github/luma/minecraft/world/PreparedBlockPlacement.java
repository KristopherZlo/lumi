package io.github.luma.minecraft.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

public record PreparedBlockPlacement(
        BlockPos pos,
        BlockState state,
        CompoundTag blockEntityTag
) {
}
