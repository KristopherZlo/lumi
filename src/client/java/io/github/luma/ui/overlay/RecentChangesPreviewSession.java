package io.github.luma.ui.overlay;

import io.github.luma.domain.model.UndoRedoAction;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Pins the recent-action preview selected at the start of an overlay hold.
 */
final class RecentChangesPreviewSession {

    private PinnedPreview pinnedPreview;

    PinnedPreview request(
            String projectId,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget,
            Supplier<ActionSnapshot> snapshotSupplier
    ) {
        RecentChangesOverlayCoordinator.PreviewTarget normalizedTarget = Objects.requireNonNullElse(
                previewTarget,
                RecentChangesOverlayCoordinator.PreviewTarget.UNDO
        );
        if (this.pinnedPreview == null || !this.pinnedPreview.key().samePreviewStream(projectId, normalizedTarget)) {
            ActionSnapshot snapshot = snapshotSupplier.get();
            this.pinnedPreview = new PinnedPreview(
                    new PreviewKey(projectId, snapshot.revision(), normalizedTarget),
                    snapshot.undoActions(),
                    snapshot.redoActions()
            );
        }
        return this.pinnedPreview;
    }

    void clear() {
        this.pinnedPreview = null;
    }

    record PreviewKey(
            String projectId,
            long revision,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget
    ) {

        PreviewKey {
            previewTarget = Objects.requireNonNullElse(
                    previewTarget,
                    RecentChangesOverlayCoordinator.PreviewTarget.UNDO
            );
        }

        private boolean samePreviewStream(
                String projectId,
                RecentChangesOverlayCoordinator.PreviewTarget previewTarget
        ) {
            return Objects.equals(this.projectId, projectId)
                    && this.previewTarget == previewTarget;
        }
    }

    record ActionSnapshot(
            long revision,
            List<UndoRedoAction> undoActions,
            List<UndoRedoAction> redoActions
    ) {

        ActionSnapshot {
            undoActions = undoActions == null ? List.of() : List.copyOf(undoActions);
            redoActions = redoActions == null ? List.of() : List.copyOf(redoActions);
        }
    }

    record PinnedPreview(
            PreviewKey key,
            List<UndoRedoAction> undoActions,
            List<UndoRedoAction> redoActions
    ) {

        PinnedPreview {
            undoActions = undoActions == null ? List.of() : List.copyOf(undoActions);
            redoActions = redoActions == null ? List.of() : List.copyOf(redoActions);
        }

        boolean hasBlockPreview() {
            return switch (this.key.previewTarget()) {
                case REDO -> hasRedoBlockPreview(this.redoActions);
                case BOTH -> hasUndoBlockPreview(this.undoActions) || hasRedoBlockPreview(this.redoActions);
                case UNDO -> hasUndoBlockPreview(this.undoActions);
            };
        }

        private static boolean hasUndoBlockPreview(List<UndoRedoAction> actions) {
            for (UndoRedoAction action : actions) {
                if (action != null && !action.undoChanges().isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasRedoBlockPreview(List<UndoRedoAction> actions) {
            for (UndoRedoAction action : actions) {
                if (action != null && !action.redoChanges().isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }
}
