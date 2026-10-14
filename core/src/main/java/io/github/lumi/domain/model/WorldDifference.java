package io.github.lumi.domain.model;

import java.util.Map;
import java.util.Objects;

/** Sparse Merkle comparison result; payloads remain encoded until a consumer needs them. */
public record WorldDifference(
        Map<SectionKey, ObjectChange> sections,
        Map<EntityChunkKey, ObjectChange> entities) {
    public WorldDifference {
        sections = Map.copyOf(Objects.requireNonNull(sections, "sections"));
        entities = Map.copyOf(Objects.requireNonNull(entities, "entities"));
    }

    public int changeCount() {
        return Math.addExact(sections.size(), entities.size());
    }

    public boolean isEmpty() {
        return sections.isEmpty() && entities.isEmpty();
    }
}
