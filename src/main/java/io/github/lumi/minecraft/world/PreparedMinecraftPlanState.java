package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Preflighted persistent state whose native sections are decoded per bounded batch. */
record PreparedMinecraftPlanState(
        WorldStateApply.State source,
        WorldStateApply.State base,
        Map<EntityChunkKey, DecodedEntityChunk> entities,
        List<SectionKey> sectionKeys,
        List<EntityChunkKey> entityKeys)
        implements WorldStateApply.PreparedState {
    PreparedMinecraftPlanState {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(base, "base");
        entities = Map.copyOf(Objects.requireNonNull(entities, "entities"));
        sectionKeys = List.copyOf(Objects.requireNonNull(sectionKeys, "sectionKeys"));
        entityKeys = List.copyOf(Objects.requireNonNull(entityKeys, "entityKeys"));
        if (!source.sections().keySet().equals(base.sections().keySet())
                || !source.entities().keySet().equals(base.entities().keySet())
                || !entities.keySet().equals(source.entities().keySet())) {
            throw new IllegalArgumentException("Prepared Restore plan keys must match");
        }
    }
}
