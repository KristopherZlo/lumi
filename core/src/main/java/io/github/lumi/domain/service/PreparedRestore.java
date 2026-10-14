package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.PlayerSpawn;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record PreparedRestore(
        BranchRef expectedRef,
        CommitId targetCommit,
        Map<SectionKey, SectionBlob> sections,
        Map<EntityChunkKey, EntityChunkBlob> entities,
        Map<SectionKey, SectionBlob> returnSections,
        Map<EntityChunkKey, EntityChunkBlob> returnEntities,
        Map<UUID, PlayerSpawn> playerSpawns,
        Map<UUID, PlayerSpawn> returnPlayerSpawns,
        boolean restorePlayerSpawns) implements Closeable {
    public PreparedRestore {
        Objects.requireNonNull(expectedRef, "expectedRef");
        Objects.requireNonNull(targetCommit, "targetCommit");
        sections = immutable(Objects.requireNonNull(sections, "sections"));
        entities = immutable(Objects.requireNonNull(entities, "entities"));
        returnSections = immutable(Objects.requireNonNull(returnSections, "returnSections"));
        returnEntities = immutable(Objects.requireNonNull(returnEntities, "returnEntities"));
        playerSpawns = Map.copyOf(Objects.requireNonNull(playerSpawns, "playerSpawns"));
        returnPlayerSpawns = Map.copyOf(
                Objects.requireNonNull(returnPlayerSpawns, "returnPlayerSpawns"));
        if (!sections.keySet().equals(returnSections.keySet())
                || !entities.keySet().equals(returnEntities.keySet())) {
            throw new IllegalArgumentException("Restore target and return state must cover identical keys");
        }
        if (!restorePlayerSpawns && (!playerSpawns.isEmpty() || !returnPlayerSpawns.isEmpty())) {
            throw new IllegalArgumentException("Disabled player-spawn Restore must not carry spawn state");
        }
    }

    public PreparedRestore(
            BranchRef expectedRef,
            CommitId targetCommit,
            Map<SectionKey, SectionBlob> sections,
            Map<EntityChunkKey, EntityChunkBlob> entities,
            Map<SectionKey, SectionBlob> returnSections,
            Map<EntityChunkKey, EntityChunkBlob> returnEntities) {
        this(expectedRef, targetCommit, sections, entities, returnSections, returnEntities,
                Map.of(), Map.of(), false);
    }

    public int changeCount() {
        return sections.size() + entities.size()
                + (restorePlayerSpawns ? playerSpawns.size() : 0);
    }

    public PreparedRestore withReturnPlayerSpawns(
            Map<UUID, PlayerSpawn> returnSpawns) {
        if (!restorePlayerSpawns) {
            return this;
        }
        return new PreparedRestore(
                expectedRef, targetCommit, sections, entities,
                returnSections, returnEntities, playerSpawns,
                returnSpawns, true);
    }

    /**
     * Composes {@code preceding: checkpoint -> expectedRef} with this
     * {@code expectedRef -> target} plan. The returned plan owns both inputs.
     */
    public PreparedRestore composeAfter(PreparedRestore preceding) throws IOException {
        Objects.requireNonNull(preceding, "preceding");
        if (!expectedRef.equals(preceding.expectedRef)
                || !expectedRef.commit().equals(preceding.targetCommit)
                || restorePlayerSpawns != preceding.restorePlayerSpawns) {
            throw new IllegalArgumentException("Restore plans do not share an intermediate state");
        }
        try {
            requireSameIntermediate(returnSections, preceding.sections);
            requireSameIntermediate(returnEntities, preceding.entities);
            if (!returnPlayerSpawns.equals(preceding.playerSpawns)) {
                throw new IllegalArgumentException(
                        "Restore player spawns do not share an intermediate state");
            }
            List<SectionKey> sectionKeys = composedKeys(
                    sections, preceding.returnSections);
            Map<EntityChunkKey, EntityChunkBlob> targetEntities = new HashMap<>();
            Map<EntityChunkKey, EntityChunkBlob> returnEntities = new HashMap<>();
            for (EntityChunkKey key : composedKeys(entities, preceding.returnEntities)) {
                targetEntities.put(key, entities.containsKey(key)
                        ? entities.get(key) : preceding.entities.get(key));
                returnEntities.put(key, preceding.returnEntities.containsKey(key)
                        ? preceding.returnEntities.get(key) : this.returnEntities.get(key));
            }
            return new PreparedRestore(
                    expectedRef, targetCommit,
                    RestorePlanMap.compose(sectionKeys, sections, preceding.sections),
                    targetEntities,
                    RestorePlanMap.compose(
                            sectionKeys, preceding.returnSections, returnSections),
                    returnEntities, playerSpawns, preceding.returnPlayerSpawns,
                    restorePlayerSpawns);
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    public PreparedRestore materialize() throws IOException {
        return new PreparedRestore(
                expectedRef, targetCommit,
                materialize(sections), materialize(entities),
                materialize(returnSections), materialize(returnEntities),
                playerSpawns, returnPlayerSpawns, restorePlayerSpawns);
    }

    private static <K, V> Map<K, V> immutable(Map<K, V> values) {
        return values instanceof RestorePlanMap<?, ?> ? values : Map.copyOf(values);
    }

    private static <K, V> Map<K, V> materialize(Map<K, V> values) throws IOException {
        if (values instanceof RestorePlanMap<?, ?> lazy) {
            @SuppressWarnings("unchecked")
            RestorePlanMap<K, V> typed = (RestorePlanMap<K, V>) lazy;
            return typed.materialize();
        }
        return values;
    }

    private static <K, V> void requireSameIntermediate(
            Map<K, V> left, Map<K, V> right) {
        for (K key : right.keySet()) {
            if (left.containsKey(key) && !left.get(key).equals(right.get(key))) {
                throw new IllegalArgumentException(
                        "Restore plans disagree at their intermediate state: " + key);
            }
        }
    }

    private static <K, V> List<K> composedKeys(
            Map<K, V> target, Map<K, V> returnPoint) {
        var keys = new LinkedHashSet<K>(target.keySet());
        keys.addAll(returnPoint.keySet());
        keys.removeIf(key -> target.containsKey(key) && returnPoint.containsKey(key)
                && target.get(key).equals(returnPoint.get(key)));
        return List.copyOf(keys);
    }

    @Override
    public void close() throws IOException {
        try (Closeable target = RestorePlanMap.closeable(sections);
                Closeable checkpoint = RestorePlanMap.closeable(returnSections)) {
            // try-with-resources preserves both close failures.
        }
    }
}
