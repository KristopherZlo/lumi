package io.github.lumi.domain.model;

/** Coordinates of one 4x4x4 biome cell, not block coordinates. */
public record BiomePosition(int x, int y, int z) {
    public SectionKey section() { return new SectionKey(Math.floorDiv(x, 4), Math.floorDiv(y, 4), Math.floorDiv(z, 4)); }
    public int localIndex() { return (Math.floorMod(y, 4) << 4) | (Math.floorMod(z, 4) << 2) | Math.floorMod(x, 4); }
}
