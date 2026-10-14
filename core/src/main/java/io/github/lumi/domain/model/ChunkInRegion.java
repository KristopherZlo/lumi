package io.github.lumi.domain.model;

public record ChunkInRegion(int x, int z) implements Comparable<ChunkInRegion> {
    public ChunkInRegion {
        if (x < 0 || x > 31 || z < 0 || z > 31) {
            throw new IllegalArgumentException("Region-local chunk coordinates must be within 0..31");
        }
    }

    @Override
    public int compareTo(ChunkInRegion other) {
        int xOrder = Integer.compare(x, other.x);
        return xOrder != 0 ? xOrder : Integer.compare(z, other.z);
    }
}
