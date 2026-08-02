package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import java.util.LinkedHashMap;
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

    PreparedMinecraftPlanState withSource(WorldStateApply.State replacement) {
        return new PreparedMinecraftPlanState(
                replacement, base, entities, baseEntities,
                sectionKeys, entityKeys, cleanupEntityIds);
    }

    PreparedMinecraftPlanState composeAfter(
            PreparedMinecraftPlanState preceding,
            WorldStateApply.State target,
            WorldStateApply.State returnPoint) {
        Objects.requireNonNull(preceding, "preceding");
        Set<EntityChunkKey> entityKeys = target.entities().keySet();
        Map<EntityChunkKey, DecodedEntityChunk> targetEntities = compose(
                entityKeys, entities, preceding.entities);
        Map<EntityChunkKey, DecodedEntityChunk> returnEntities = compose(
                entityKeys, preceding.baseEntities, baseEntities);
        var normalizedTarget = new WorldStateApply.State(
                target.sections(), compose(
                        entityKeys, source.entities(), preceding.source.entities()),
                target.playerSpawns(), target.playerSpawnsIncluded());
        var normalizedReturn = new WorldStateApply.State(
                returnPoint.sections(),
                compose(entityKeys, preceding.base.entities(), base.entities()),
                returnPoint.playerSpawns(), returnPoint.playerSpawnsIncluded());
        return new PreparedMinecraftPlanState(
                normalizedTarget, normalizedReturn, targetEntities, returnEntities,
                MinecraftRestorePreparation.orderedSections(
                        target.sections().keySet()),
                List.copyOf(targetEntities.keySet()),
                MinecraftRestorePreparation.cleanupEntityIds(
                        normalizedTarget.entities(), normalizedReturn.entities()));
    }

    PreparedMinecraftPlanState reversed() {
        return new PreparedMinecraftPlanState(
                base, source, baseEntities, entities, sectionKeys, entityKeys,
                cleanupEntityIds);
    }

    private static <K, V> Map<K, V> compose(
            Set<K> keys, Map<K, V> primary, Map<K, V> fallback) {
        Map<K, V> composed = new LinkedHashMap<>();
        keys.forEach(key -> composed.put(
                key, primary.containsKey(key) ? primary.get(key) : fallback.get(key)));
        return Map.copyOf(composed);
    }
}
