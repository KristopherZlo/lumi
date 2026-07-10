package io.github.luma.domain.model;

import java.util.Objects;
import net.minecraft.core.BlockPos;

public record Bounds3i(BlockPoint min, BlockPoint max) {

    public Bounds3i {
        BlockPoint first = Objects.requireNonNull(min, "min");
        BlockPoint second = Objects.requireNonNull(max, "max");
        min = new BlockPoint(
                Math.min(first.x(), second.x()),
                Math.min(first.y(), second.y()),
                Math.min(first.z(), second.z())
        );
        max = new BlockPoint(
                Math.max(first.x(), second.x()),
                Math.max(first.y(), second.y()),
                Math.max(first.z(), second.z())
        );
    }

    public static Bounds3i of(BlockPos a, BlockPos b) {
        return new Bounds3i(
                new BlockPoint(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                new BlockPoint(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))
        );
    }

    public int sizeX() {
        return Math.toIntExact(span(this.min.x(), this.max.x()));
    }

    public int sizeY() {
        return Math.toIntExact(span(this.min.y(), this.max.y()));
    }

    public int sizeZ() {
        return Math.toIntExact(span(this.min.z(), this.max.z()));
    }

    public long volume() {
        return saturatedMultiply(
                saturatedMultiply(span(this.min.x(), this.max.x()), span(this.min.y(), this.max.y())),
                span(this.min.z(), this.max.z())
        );
    }

    public boolean contains(BlockPoint point) {
        if (point == null) {
            return false;
        }
        return point.x() >= this.min.x()
                && point.x() <= this.max.x()
                && point.y() >= this.min.y()
                && point.y() <= this.max.y()
                && point.z() >= this.min.z()
                && point.z() <= this.max.z();
    }

    private static long span(int min, int max) {
        return (long) max - min + 1L;
    }

    private static long saturatedMultiply(long left, long right) {
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }
}
