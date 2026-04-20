package io.github.luma.domain.model;

import net.minecraft.core.BlockPos;

public record ChunkPoint(int x, int z) {

    public static ChunkPoint from(BlockPos pos) {
        return new ChunkPoint(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static ChunkPoint from(BlockPoint pos) {
        return new ChunkPoint(pos.x() >> 4, pos.z() >> 4);
    }
}
