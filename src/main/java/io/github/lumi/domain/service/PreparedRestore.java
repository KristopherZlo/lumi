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

public record PreparedRestore(
        BranchRef expectedRef,
        CommitId targetCommit,
        Map<SectionKey, SectionBlob> sections,
        Map<EntityChunkKey, EntityChunkBlob> entities,
        Map<SectionKey, SectionBlob> returnSections,
        Map<EntityChunkKey, EntityChunkBlob> returnEntities,
        Map<UUID, PlayerSpawn> playerSpawns,
        Map<UUID, PlayerSpawn> returnPlayerSpawns,
        boolean restorePlayerSpawns) {
    public PreparedRestore {
        Objects.requireNonNull(expectedRef, "expectedRef");
        Objects.requireNonNull(targetCommit, "targetCommit");
        sections = Map.copyOf(Objects.requireNonNull(sections, "sections"));
        entities = Map.copyOf(Objects.requireNonNull(entities, "entities"));
        returnSections = Map.copyOf(Objects.requireNonNull(returnSections, "returnSections"));
        returnEntities = Map.copyOf(Objects.requireNonNull(returnEntities, "returnEntities"));
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
}
