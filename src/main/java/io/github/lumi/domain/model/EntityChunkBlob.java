package io.github.lumi.domain.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record EntityChunkBlob(List<EntityState> entities) {
    public EntityChunkBlob {
        entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        var identities = new HashSet<>();
        if (entities.stream().anyMatch(entity -> !identities.add(entity.id()))) {
            throw new IllegalArgumentException("Entity UUIDs must be unique within a chunk");
        }
    }
}
