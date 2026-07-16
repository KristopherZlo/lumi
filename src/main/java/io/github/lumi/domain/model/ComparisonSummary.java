package io.github.lumi.domain.model;

import java.util.Map;
import java.util.List;
import java.util.Objects;

/** Immutable read-only comparison result suitable for client publication. */
public record ComparisonSummary(
        CommitId before,
        CommitId after,
        int changedSections,
        int changedEntityChunks,
        List<SectionKey> sectionPreview,
        Map<String, MaterialDelta> materials) {
    public ComparisonSummary {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        sectionPreview = List.copyOf(
                Objects.requireNonNull(sectionPreview, "sectionPreview"));
        materials = Map.copyOf(Objects.requireNonNull(materials, "materials"));
        if (before.equals(after)) {
            throw new IllegalArgumentException("Comparison commits must differ");
        }
        if (changedSections < 0 || changedEntityChunks < 0
                || sectionPreview.size() > changedSections) {
            throw new IllegalArgumentException("Comparison counts cannot be negative");
        }
    }

    public ComparisonSummary(
            CommitId before,
            CommitId after,
            int changedSections,
            int changedEntityChunks,
            Map<String, MaterialDelta> materials) {
        this(before, after, changedSections, changedEntityChunks, List.of(), materials);
    }
}
