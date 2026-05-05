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
                    snapshot.actions()
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

    record ActionSnapshot(long revision, List<UndoRedoAction> actions) {

        ActionSnapshot {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    record PinnedPreview(PreviewKey key, List<UndoRedoAction> actions) {

        PinnedPreview {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }
}
