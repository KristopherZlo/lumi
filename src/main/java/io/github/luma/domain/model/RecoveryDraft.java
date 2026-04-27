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
        List<StoredBlockChange> changes,
        List<StoredEntityChange> entityChanges
) {

    public RecoveryDraft {
        changes = changes == null ? List.of() : List.copyOf(changes);
        entityChanges = entityChanges == null ? List.of() : List.copyOf(entityChanges);
    }

    public RecoveryDraft(
            String projectId,
            String variantId,
            String baseVersionId,
            String actor,
            WorldMutationSource mutationSource,
            Instant startedAt,
            Instant updatedAt,
            List<StoredBlockChange> changes
    ) {
        this(projectId, variantId, baseVersionId, actor, mutationSource, startedAt, updatedAt, changes, List.of());
    }

    public boolean isEmpty() {
        return this.changes.isEmpty() && this.entityChanges.isEmpty();
    }

    public int totalChangeCount() {
        return this.changes.size() + this.entityChanges.size();
    }
}
