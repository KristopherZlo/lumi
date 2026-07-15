package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import java.util.Map;
import java.util.Objects;

public record PreparedMinecraftState(
        WorldStateApply.State source,
        Map<SectionKey, DecodedSection> sections,
        Map<EntityChunkKey, DecodedEntityChunk> entities)
        implements WorldStateApply.PreparedState {
    public PreparedMinecraftState {
        Objects.requireNonNull(source, "source");
        sections = Map.copyOf(Objects.requireNonNull(sections, "sections"));
        entities = Map.copyOf(Objects.requireNonNull(entities, "entities"));
        if (!sections.keySet().equals(source.sections().keySet())
                || !entities.keySet().equals(source.entities().keySet())) {
            throw new IllegalArgumentException("Prepared and persistent keys must match");
        }
    }
}
