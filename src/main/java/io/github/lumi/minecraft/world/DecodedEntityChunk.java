package io.github.lumi.minecraft.world;

import java.util.List;
import java.util.Objects;

public record DecodedEntityChunk(List<DecodedEntity> entities) {
    public DecodedEntityChunk {
        entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
    }
}
