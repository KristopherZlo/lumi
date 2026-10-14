package io.github.lumi.domain.model;

import java.util.Map;
import java.util.Objects;

public record RegionTree(Map<ChunkInRegion, ObjectId> chunks) {
    public RegionTree {
        chunks = Map.copyOf(Objects.requireNonNull(chunks, "chunks"));
    }
}
