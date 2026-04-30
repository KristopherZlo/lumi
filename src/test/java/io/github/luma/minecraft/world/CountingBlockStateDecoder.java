package io.github.luma.minecraft.world;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

final class CountingBlockStateDecoder implements BlockStateDecoder {

    private final Map<String, Integer> callsByName = new LinkedHashMap<>();

    @Override
    public BlockState decode(ServerLevel level, CompoundTag tag) throws IOException {
        String name = tag == null ? "minecraft:air" : tag.getString("Name").orElse("minecraft:air");
        this.callsByName.merge(name, 1, Integer::sum);
        return BlockStateNbtCodec.deserializeBlockState(level, tag);
    }

    int callsFor(String name) {
        return this.callsByName.getOrDefault(name, 0);
    }
}
