package io.github.lumi.domain.service;

import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

public record CapturedWorldState(
        Map<SectionKey, SectionBlob> sections,
        Map<EntityChunkKey, EntityChunkBlob> entities,
        WorkingIndexSnapshot generations,
        CommitStatistics statistics) {
    public CapturedWorldState {
        sections = Map.copyOf(Objects.requireNonNull(sections, "sections"));
        entities = Map.copyOf(Objects.requireNonNull(entities, "entities"));
        Objects.requireNonNull(generations, "generations");
        Objects.requireNonNull(statistics, "statistics");
        var capturedKeys = new HashSet<HistoryKey>(sections.keySet());
        capturedKeys.addAll(entities.keySet());
        if (!capturedKeys.equals(generations.generations().keySet())) {
            throw new IllegalArgumentException("Captured payloads and dirty generations must have identical keys");
        }
    }
}
