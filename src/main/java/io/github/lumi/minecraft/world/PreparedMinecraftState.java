package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record PreparedMinecraftState(
        WorldStateApply.State source,
        Map<SectionKey, DecodedSection> sections,
        Map<EntityChunkKey, DecodedEntityChunk> entities,
        List<SectionKey> sectionKeys,
        List<EntityChunkKey> entityKeys)
        implements WorldStateApply.PreparedState {
    public PreparedMinecraftState(
            WorldStateApply.State source,
            Map<SectionKey, DecodedSection> sections,
            Map<EntityChunkKey, DecodedEntityChunk> entities) {
        this(source, sections, entities,
                List.copyOf(sections.keySet()), List.copyOf(entities.keySet()));
    }

    public PreparedMinecraftState {
        Objects.requireNonNull(source, "source");
        sections = Map.copyOf(Objects.requireNonNull(sections, "sections"));
        entities = Map.copyOf(Objects.requireNonNull(entities, "entities"));
        sectionKeys = List.copyOf(Objects.requireNonNull(sectionKeys, "sectionKeys"));
        entityKeys = List.copyOf(Objects.requireNonNull(entityKeys, "entityKeys"));
        if (!sections.keySet().equals(source.sections().keySet())
                || !entities.keySet().equals(source.entities().keySet())) {
            throw new IllegalArgumentException("Prepared and persistent keys must match");
        }
        if (sectionKeys.size() != sections.size()
                || !Set.copyOf(sectionKeys).equals(sections.keySet())
                || entityKeys.size() != entities.size()
                || !Set.copyOf(entityKeys).equals(entities.keySet())) {
            throw new IllegalArgumentException("Prepared key order must contain every key once");
        }
    }
}
