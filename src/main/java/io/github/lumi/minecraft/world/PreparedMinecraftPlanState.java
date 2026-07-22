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
        Map<EntityChunkKey, DecodedEntityChunk> baseEntities,
        List<SectionKey> sectionKeys,
        List<EntityChunkKey> entityKeys)
        implements WorldStateApply.PreparedState {
    PreparedMinecraftPlanState {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(base, "base");
        entities = Map.copyOf(Objects.requireNonNull(entities, "entities"));
        baseEntities = Map.copyOf(Objects.requireNonNull(baseEntities, "baseEntities"));
        sectionKeys = List.copyOf(Objects.requireNonNull(sectionKeys, "sectionKeys"));
        entityKeys = List.copyOf(Objects.requireNonNull(entityKeys, "entityKeys"));
        if (!source.sections().keySet().equals(base.sections().keySet())
                || !source.entities().keySet().equals(base.entities().keySet())
                || !entities.keySet().equals(source.entities().keySet())
                || !baseEntities.keySet().equals(base.entities().keySet())) {
            throw new IllegalArgumentException("Prepared Restore plan keys must match");
        }
    }

    PreparedMinecraftPlanState withSectionKeys(List<SectionKey> ordered) {
        return new PreparedMinecraftPlanState(
                source, base, entities, baseEntities, ordered, entityKeys);
    }

    PreparedMinecraftPlanState reversed() {
        return new PreparedMinecraftPlanState(
                base, source, baseEntities, entities, sectionKeys, entityKeys);
    }
}
