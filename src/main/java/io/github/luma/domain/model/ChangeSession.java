package io.github.luma.domain.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ChangeSession(
        String id,
        String projectId,
        String variantId,
        String baseVersionId,
        String actor,
        WorldMutationSource mutationSource,
        Instant startedAt,
        Instant updatedAt,
        Map<BlockPoint, BlockChangeRecord> changes
) {

    public static ChangeSession create(
            String id,
            String projectId,
            String variantId,
            String baseVersionId,
            String actor,
            WorldMutationSource mutationSource,
            Instant now
    ) {
        return new ChangeSession(id, projectId, variantId, baseVersionId, actor, mutationSource, now, now, new LinkedHashMap<>());
    }

    public ChangeSession addChange(BlockChangeRecord change, Instant now) {
        LinkedHashMap<BlockPoint, BlockChangeRecord> nextChanges = new LinkedHashMap<>(this.changes);
        BlockChangeRecord current = nextChanges.get(change.pos());
        BlockChangeRecord merged = current == null
                ? change
                : current.withLatestState(change.newState(), change.newBlockEntityNbt());

        if (merged.isNoOp()) {
            nextChanges.remove(change.pos());
        } else {
            nextChanges.put(change.pos(), merged);
        }

        return new ChangeSession(
                this.id,
                this.projectId,
                this.variantId,
                this.baseVersionId,
                this.actor,
                this.mutationSource,
                this.startedAt,
                now,
                nextChanges
        );
    }

    public boolean isEmpty() {
        return this.changes.isEmpty();
    }

    public List<BlockChangeRecord> orderedChanges() {
        return List.copyOf(this.changes.values());
    }
}
