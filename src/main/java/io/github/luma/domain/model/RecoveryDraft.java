package io.github.luma.domain.model;

import java.time.Instant;
import java.util.List;

public record RecoveryDraft(
        String projectId,
        String variantId,
        String baseVersionId,
        String actor,
        WorldMutationSource mutationSource,
        Instant startedAt,
        Instant updatedAt,
        List<StoredBlockChange> changes
) {

    public boolean isEmpty() {
        return this.changes == null || this.changes.isEmpty();
    }
}
