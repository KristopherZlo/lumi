package io.github.lumi.domain.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One persistent entity's location and state in an immutable world. */
public record EntityPlacement(EntityChunkKey chunk, EntityState state) {
    public EntityPlacement {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(state, "state");
    }

    public static Map<UUID, EntityPlacement> index(Map<EntityChunkKey, EntityChunkBlob> chunks) {
        Map<UUID, EntityPlacement> result = new HashMap<>();
        chunks.forEach((key, blob) -> blob.entities().forEach(entity -> {
            if (result.putIfAbsent(entity.id(), new EntityPlacement(key, entity)) != null) {
                throw new IllegalArgumentException("Entity UUID appears in multiple chunks: " + entity.id());
            }
        }));
        return result;
    }
}
