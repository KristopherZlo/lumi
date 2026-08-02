package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.PlayerSpawn;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.io.Closeable;
import java.io.IOException;

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

    @Override
    public void close() throws IOException {
        try (Closeable target = closeable(sections);
                Closeable checkpoint = closeable(returnSections)) {
            // try-with-resources preserves both close failures.
        }
    }

    private static Closeable closeable(Map<?, ?> values) {
        return values instanceof Closeable closeable ? closeable : () -> { };
    }
}
