package io.github.luma.domain.model;

import java.time.Instant;

public record RecoveryJournalEntry(
        Instant timestamp,
        String type,
        String message,
        String versionId,
        String variantId
) {
}
