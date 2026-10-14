package io.github.lumi.domain.model;

public record RegionCoordinate(int x, int z) implements Comparable<RegionCoordinate> {
    @Override
    public int compareTo(RegionCoordinate other) {
        int xOrder = Integer.compare(x, other.x);
        return xOrder != 0 ? xOrder : Integer.compare(z, other.z);
    }
}
