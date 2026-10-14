package io.github.lumi.domain.model;

import java.util.Map;
import java.util.Objects;

public record DimensionTree(Map<RegionCoordinate, ObjectId> regions) {
    public DimensionTree {
        regions = Map.copyOf(Objects.requireNonNull(regions, "regions"));
    }
}
