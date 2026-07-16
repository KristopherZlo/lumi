package io.github.lumi.domain.model;

import java.util.Map;
import java.util.Objects;

/** Immutable read-only comparison result suitable for client publication. */
public record ComparisonSummary(
        CommitId before,
        CommitId after,
        int changedSections,
        int changedEntityChunks,
        Map<String, MaterialDelta> materials) {
    public ComparisonSummary {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        materials = Map.copyOf(Objects.requireNonNull(materials, "materials"));
        if (before.equals(after)) {
            throw new IllegalArgumentException("Comparison commits must differ");
        }
        if (changedSections < 0 || changedEntityChunks < 0) {
            throw new IllegalArgumentException("Comparison counts cannot be negative");
        }
    }
}
