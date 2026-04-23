package io.github.luma.domain.model;

import java.time.Instant;

public record ImportedHistoryProjectSummary(
        String projectName,
        String variantId,
        String variantName,
        String headVersionId,
        Instant updatedAt
) {
}
