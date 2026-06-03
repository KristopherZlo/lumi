package io.github.luma.domain.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Collapses persisted world changes while preserving first-old/latest-new semantics.
 */
public final class StoredChangeAccumulator {

    private final LinkedHashMap<BlockPoint, StoredBlockChange> blockChanges = new LinkedHashMap<>();
    private final LinkedHashMap<String, StoredEntityChange> entityChanges = new LinkedHashMap<>();

    public void addBlockChange(StoredBlockChange change) {
        mergeBlockChange(this.blockChanges, change);
    }

    public void addEntityChange(StoredEntityChange change) {
        mergeEntityChange(this.entityChanges, change);
    }

    static void mergeBlockChange(LinkedHashMap<BlockPoint, StoredBlockChange> target, StoredBlockChange change) {
        if (target == null || change == null) {
            return;
        }
        BlockPoint key = change.pos();
        StoredBlockChange current = target.get(key);
        StoredBlockChange merged = current == null
                ? change
                : current.withLatestChange(change);
        if (merged.isNoOp()) {
            target.remove(key);
        } else {
            target.put(key, merged);
        }
    }

    static void mergeEntityChange(LinkedHashMap<String, StoredEntityChange> target, StoredEntityChange change) {
        mergeEntityChange(target, change, false);
    }

    static void mergeUndoableEntityChange(LinkedHashMap<String, StoredEntityChange> target, StoredEntityChange change) {
        mergeEntityChange(target, change, true);
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

    private static void mergeEntityChange(
            LinkedHashMap<String, StoredEntityChange> target,
            StoredEntityChange change,
            boolean resetInitialStateForSpawnMerge
    ) {
        if (target == null || change == null || change.entityId() == null || change.entityId().isBlank()) {
            return;
        }
        StoredEntityChange current = target.get(change.entityId());
        StoredEntityChange merged = current == null
                ? change
                : current.withLatestState(change.newValue());
        if (current != null && resetInitialStateForSpawnMerge && change.isSpawn()) {
            merged = merged.withInitialState(null);
        }
        if (merged.isNoOp()) {
            target.remove(change.entityId());
        } else {
            target.put(change.entityId(), merged);
        }
    }
}
