package io.github.luma.ui.overlay;

import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.List;
import java.util.Objects;

/**
 * Immutable client-side snapshot of the pending draft overlay source.
 */
public record PendingChangesOverlaySnapshot(
        String projectId,
        long revision,
        List<StoredBlockChange> blockChanges,
        int entityChangeCount
) {

    public PendingChangesOverlaySnapshot {
        projectId = projectId == null ? "" : projectId;
        blockChanges = blockChanges == null ? List.of() : List.copyOf(blockChanges);
    }

    public static PendingChangesOverlaySnapshot fromDraft(String projectId, RecoveryDraft draft) {
        if (draft == null || draft.isEmpty()) {
            return empty(projectId);
        }
        return new PendingChangesOverlaySnapshot(
                projectId,
                revision(projectId, draft),
                draft.changes(),
                draft.entityChanges().size()
        );
    }

    public static PendingChangesOverlaySnapshot empty(String projectId) {
        return new PendingChangesOverlaySnapshot(projectId, 0L, List.of(), 0);
    }

    public boolean isEmpty() {
        return this.blockChanges.isEmpty() && this.entityChangeCount == 0;
    }

    private static long revision(String projectId, RecoveryDraft draft) {
        int hash = Objects.hash(
                projectId,
                draft.updatedAt(),
                draft.changes(),
                draft.entityChanges()
        );
        return Integer.toUnsignedLong(hash);
    }
}
