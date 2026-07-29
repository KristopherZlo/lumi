package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Preflighted persistent state whose native sections are decoded per bounded batch. */
record PreparedMinecraftPlanState(
        WorldStateApply.State source,
        WorldStateApply.State base,
        Map<EntityChunkKey, DecodedEntityChunk> entities,
        Map<EntityChunkKey, DecodedEntityChunk> baseEntities,
        List<SectionKey> sectionKeys,
        List<EntityChunkKey> entityKeys,
        Set<UUID> cleanupEntityIds)
        implements WorldStateApply.PreparedState {
    PreparedMinecraftPlanState {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(base, "base");
        entities = Map.copyOf(Objects.requireNonNull(entities, "entities"));
        baseEntities = Map.copyOf(Objects.requireNonNull(baseEntities, "baseEntities"));
        sectionKeys = List.copyOf(Objects.requireNonNull(sectionKeys, "sectionKeys"));
        entityKeys = List.copyOf(Objects.requireNonNull(entityKeys, "entityKeys"));
        cleanupEntityIds = Set.copyOf(
                Objects.requireNonNull(cleanupEntityIds, "cleanupEntityIds"));
        if (!source.sections().keySet().equals(base.sections().keySet())
                || !source.entities().keySet().equals(base.entities().keySet())
                || !entities.keySet().equals(source.entities().keySet())
                || !baseEntities.keySet().equals(base.entities().keySet())) {
            throw new IllegalArgumentException("Prepared Restore plan keys must match");
        }
    }

    PreparedMinecraftPlanState withOrder(
            List<SectionKey> orderedSections,
            List<EntityChunkKey> orderedEntities) {
        return new PreparedMinecraftPlanState(
                source, base, entities, baseEntities,
                orderedSections, orderedEntities, cleanupEntityIds);
    }

    PreparedMinecraftPlanState reversed() {
        return new PreparedMinecraftPlanState(
                base, source, baseEntities, entities, sectionKeys, entityKeys,
                cleanupEntityIds);
    }
}
