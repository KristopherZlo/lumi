package io.github.luma.minecraft.world;

import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

@FunctionalInterface
interface BlockStateDecoder {

    BlockState decode(ServerLevel level, CompoundTag tag) throws IOException;
}
