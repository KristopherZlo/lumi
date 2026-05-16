package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BuilderChangeSurfacePolicy;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.UndoRedoAction;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Pins the recent-action preview selected at the start of an overlay hold.
 */
final class RecentChangesPreviewSession {

    private static final long UNTRACKED_STREAM_REVISION = Long.MIN_VALUE;
    private static final BuilderChangeSurfacePolicy BUILDER_SURFACE = new BuilderChangeSurfacePolicy();

    private PinnedPreview pinnedPreview;

    PinnedPreview request(
            String projectId,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget,
            Supplier<ActionSnapshot> snapshotSupplier
    ) {
        return this.request(projectId, previewTarget, UNTRACKED_STREAM_REVISION, snapshotSupplier);
    }

    PinnedPreview request(
            String projectId,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget,
            long streamRevision,
            Supplier<ActionSnapshot> snapshotSupplier
    ) {
        RecentChangesOverlayCoordinator.PreviewTarget normalizedTarget = Objects.requireNonNullElse(
                previewTarget,
                RecentChangesOverlayCoordinator.PreviewTarget.UNDO
        );
        if (this.needsNewPreview(projectId, normalizedTarget, streamRevision)) {
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

    private boolean needsNewPreview(
            String projectId,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget,
            long streamRevision
    ) {
        if (this.pinnedPreview == null || !this.pinnedPreview.key().samePreviewStream(projectId, previewTarget)) {
            return true;
        }
        return streamRevision != UNTRACKED_STREAM_REVISION
                && this.pinnedPreview.key().revision() != streamRevision;
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
                if (action != null && hasVisibleBlockChanges(action.undoChanges())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasRedoBlockPreview(List<UndoRedoAction> actions) {
            for (UndoRedoAction action : actions) {
                if (action != null && hasVisibleBlockChanges(action.redoChanges())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasVisibleBlockChanges(List<StoredBlockChange> changes) {
            return BUILDER_SURFACE.hasVisibleBlockChanges(changes);
        }
    }
}
