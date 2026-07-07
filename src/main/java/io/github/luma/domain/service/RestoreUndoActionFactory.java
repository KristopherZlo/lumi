package io.github.luma.domain.service;

import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import java.util.List;
import java.util.UUID;

/**
 * Builds live undo actions that mirror restore-style world mutations.
 */
final class RestoreUndoActionFactory {

    RestoreUndoAction quickRollbackUndoAction(
            String projectId,
            String dimensionId,
            String targetVersionId,
            RecoveryDraft pendingDraft
    ) {
        if (pendingDraft == null || pendingDraft.isEmpty()) {
            return null;
        }
        List<StoredBlockChange> changes = pendingDraft.changes().stream()
                .map(StoredBlockChange::inverse)
                .toList();
        List<StoredEntityChange> entityChanges = pendingDraft.entityChanges().stream()
                .map(StoredEntityChange::inverse)
                .toList();
        if (changes.isEmpty() && entityChanges.isEmpty()) {
            return null;
        }
        return new RestoreUndoAction(
                "quick-rollback-" + targetVersionId + "-" + UUID.randomUUID(),
                "Lumi quick rollback",
                projectId,
                dimensionId,
                changes,
                entityChanges
        );
    }

    RestoreUndoAction restoreUndoAction(
            String projectId,
            String dimensionId,
            String targetVersionId,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges
    ) {
        List<StoredBlockChange> blockChanges = changes == null ? List.of() : List.copyOf(changes);
        List<StoredEntityChange> entityChangeList = entityChanges == null ? List.of() : List.copyOf(entityChanges);
        if (blockChanges.isEmpty() && entityChangeList.isEmpty()) {
            return null;
        }
        return new RestoreUndoAction(
                "restore-" + normalizedTargetVersionId(targetVersionId) + "-" + UUID.randomUUID(),
                "Lumi quick rollback",
                projectId,
                dimensionId,
                blockChanges,
                entityChangeList
        );
    }

    private static String normalizedTargetVersionId(String targetVersionId) {
        return targetVersionId == null || targetVersionId.isBlank() ? "target" : targetVersionId;
    }
}

record RestoreUndoAction(
        String actionId,
        String actor,
        String projectId,
        String dimensionId,
        List<StoredBlockChange> changes,
        List<StoredEntityChange> entityChanges
) {

    RestoreUndoAction {
        changes = changes == null ? List.of() : List.copyOf(changes);
        entityChanges = entityChanges == null ? List.of() : List.copyOf(entityChanges);
    }

    boolean isEmpty() {
        return this.changes.isEmpty() && this.entityChanges.isEmpty();
    }
}
