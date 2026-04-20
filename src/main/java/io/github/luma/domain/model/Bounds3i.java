package io.github.luma.domain.model;

import net.minecraft.core.BlockPos;

public record Bounds3i(BlockPoint min, BlockPoint max) {

    public static Bounds3i of(BlockPos a, BlockPos b) {
        return new Bounds3i(
                new BlockPoint(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                new BlockPoint(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))
        );
    }

    public int sizeX() {
        return this.max.x() - this.min.x() + 1;
    }

    public int sizeY() {
        return this.max.y() - this.min.y() + 1;
    }

    public int sizeZ() {
        return this.max.z() - this.min.z() + 1;
    }

    public long volume() {
        return (long) this.sizeX() * this.sizeY() * this.sizeZ();
    }
}
