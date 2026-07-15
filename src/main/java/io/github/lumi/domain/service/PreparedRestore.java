package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.util.Map;
import java.util.Objects;

public record PreparedRestore(
        BranchRef expectedRef,
        CommitId targetCommit,
        Map<SectionKey, SectionBlob> sections,
        Map<EntityChunkKey, EntityChunkBlob> entities,
        Map<SectionKey, SectionBlob> returnSections,
        Map<EntityChunkKey, EntityChunkBlob> returnEntities) {
    public PreparedRestore {
        Objects.requireNonNull(expectedRef, "expectedRef");
        Objects.requireNonNull(targetCommit, "targetCommit");
        sections = Map.copyOf(Objects.requireNonNull(sections, "sections"));
        entities = Map.copyOf(Objects.requireNonNull(entities, "entities"));
        returnSections = Map.copyOf(Objects.requireNonNull(returnSections, "returnSections"));
        returnEntities = Map.copyOf(Objects.requireNonNull(returnEntities, "returnEntities"));
        if (!sections.keySet().equals(returnSections.keySet())
                || !entities.keySet().equals(returnEntities.keySet())) {
            throw new IllegalArgumentException("Restore target and return state must cover identical keys");
        }
    }

    public int changeCount() {
        return sections.size() + entities.size();
    }
}
