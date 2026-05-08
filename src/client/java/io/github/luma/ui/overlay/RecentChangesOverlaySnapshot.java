package io.github.luma.ui.overlay;

import io.github.luma.domain.model.UndoRedoAction;
import java.util.List;

/**
 * Immutable source data for the held recent-action world preview.
 */
record RecentChangesOverlaySnapshot(
        String projectId,
        long revision,
        List<UndoRedoAction> undoActions,
        List<UndoRedoAction> redoActions
) {

    RecentChangesOverlaySnapshot {
        projectId = projectId == null ? "" : projectId;
        undoActions = undoActions == null ? List.of() : List.copyOf(undoActions);
        redoActions = redoActions == null ? List.of() : List.copyOf(redoActions);
    }

    static RecentChangesOverlaySnapshot forTarget(
            String projectId,
            long revision,
            List<UndoRedoAction> actions,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget
    ) {
        if (previewTarget == RecentChangesOverlayCoordinator.PreviewTarget.REDO) {
            return new RecentChangesOverlaySnapshot(projectId, revision, List.of(), actions);
        }
        return new RecentChangesOverlaySnapshot(projectId, revision, actions, List.of());
    }

    int actionCount() {
        return this.undoActions.size() + this.redoActions.size();
    }
}
