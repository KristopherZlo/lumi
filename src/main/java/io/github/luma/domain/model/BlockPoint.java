package io.github.luma.domain.model;

import net.minecraft.core.BlockPos;

public record BlockPoint(int x, int y, int z) {

    public static BlockPoint from(BlockPos pos) {
        return new BlockPoint(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockPos toBlockPos() {
        return new BlockPos(this.x, this.y, this.z);
    }
}
