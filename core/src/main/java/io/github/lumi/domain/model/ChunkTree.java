package io.github.lumi.domain.model;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ChunkTree(Map<Integer, ObjectId> sections, Optional<ObjectId> entities) {
    public ChunkTree {
        sections = Map.copyOf(Objects.requireNonNull(sections, "sections"));
        entities = Objects.requireNonNull(entities, "entities");
    }
}
