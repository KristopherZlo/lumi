package io.github.luma.domain.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Collapses persisted world changes while preserving first-old/latest-new semantics.
 */
public final class StoredChangeAccumulator {

    private final LinkedHashMap<Long, StoredBlockChange> blockChanges = new LinkedHashMap<>();
    private final LinkedHashMap<String, StoredEntityChange> entityChanges = new LinkedHashMap<>();

    public void addBlockChange(StoredBlockChange change) {
        if (change == null) {
            return;
        }
        long key = BlockPos.asLong(change.pos().x(), change.pos().y(), change.pos().z());
        StoredBlockChange current = this.blockChanges.get(key);
        StoredBlockChange merged = current == null
                ? change
                : current.withLatestState(change.newValue());
        if (merged.isNoOp()) {
            this.blockChanges.remove(key);
        } else {
            this.blockChanges.put(key, merged);
        }
    }

    public void addEntityChange(StoredEntityChange change) {
        if (change == null || change.entityId() == null || change.entityId().isBlank()) {
            return;
        }
        StoredEntityChange current = this.entityChanges.get(change.entityId());
        StoredEntityChange merged = current == null
                ? change
                : current.withLatestState(change.newValue());
        if (merged.isNoOp()) {
            this.entityChanges.remove(change.entityId());
        } else {
            this.entityChanges.put(change.entityId(), merged);
        }
    }

    public void addBlockChanges(List<StoredBlockChange> changes) {
        for (StoredBlockChange change : changes == null ? List.<StoredBlockChange>of() : changes) {
            this.addBlockChange(change);
        }
    }

    public void addEntityChanges(List<StoredEntityChange> changes) {
        for (StoredEntityChange change : changes == null ? List.<StoredEntityChange>of() : changes) {
            this.addEntityChange(change);
        }
    }

    public List<StoredBlockChange> blockChanges() {
        return List.copyOf(this.blockChanges.values());
    }

    public List<StoredEntityChange> entityChanges() {
        return List.copyOf(this.entityChanges.values());
    }

    public RecoveryDraft toDraft(
            String projectId,
            String variantId,
            String baseVersionId,
            String actor,
            WorldMutationSource mutationSource,
            Instant startedAt,
            Instant updatedAt
    ) {
        return new RecoveryDraft(
                projectId,
                variantId,
                baseVersionId,
                actor,
                mutationSource,
                startedAt,
                updatedAt,
                this.blockChanges(),
                this.entityChanges()
        );
    }
}
