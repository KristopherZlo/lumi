package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.UUID;

public record EntityState(UUID id, String type, CanonicalNbt nbt) {
    public EntityState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(nbt, "nbt");
        if (type.isBlank()) {
            throw new IllegalArgumentException("Entity type cannot be blank");
        }
    }
}
