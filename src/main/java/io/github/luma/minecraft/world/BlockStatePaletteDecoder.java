package io.github.luma.minecraft.world;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

final class BlockStatePaletteDecoder implements BlockStateDecoder {

    private final Map<CompoundTag, BlockState> decodedStates = new LinkedHashMap<>();

    @Override
    public BlockState decode(ServerLevel level, CompoundTag tag) throws IOException {
        CompoundTag key = tag == null ? new CompoundTag() : tag.copy();
        BlockState cached = this.decodedStates.get(key);
        if (cached != null) {
            return cached;
        }
        BlockState decoded = BlockStateNbtCodec.deserializeBlockState(level, tag);
        this.decodedStates.put(key, decoded);
        return decoded;
    }
}
