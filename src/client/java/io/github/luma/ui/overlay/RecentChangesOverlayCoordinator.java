package io.github.luma.ui.overlay;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.ui.controller.ClientProjectAccess;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Loads and exposes the recent action overlay while Alt is held.
 */
public final class RecentChangesOverlayCoordinator {

    private static final RecentChangesOverlayCoordinator INSTANCE = new RecentChangesOverlayCoordinator();

    private final ProjectService projectService = new ProjectService();

    private RecentChangesOverlayCoordinator() {
    }

    public static RecentChangesOverlayCoordinator getInstance() {
        return INSTANCE;
    }

    public void tick(Minecraft client, boolean altHeld) {
        this.tick(client, altHeld, PreviewTarget.UNDO);
    }

    public void tick(Minecraft client, boolean altHeld, PreviewTarget previewTarget) {
        if (client == null) {
            this.logSkip("no-client", previewTarget);
            RecentChangesOverlayRenderer.clear();
            return;
        }
        if (client.player == null) {
            this.logSkip("no-player", previewTarget);
            RecentChangesOverlayRenderer.clear();
            return;
        }
        if (client.level == null) {
            this.logSkip("no-level", previewTarget);
            RecentChangesOverlayRenderer.clear();
            return;
        }
        if (!altHeld) {
            this.logSkip("overlay-key-not-held", previewTarget);
            RecentChangesOverlayRenderer.clear();
            return;
        }
        if (CompareOverlayRenderer.visible()) {
            this.logSkip("compare-overlay-visible", previewTarget);
            RecentChangesOverlayRenderer.clear();
            return;
        }

        try {
            var project = ClientProjectAccess.findCurrentWorldProject(client, this.projectService);
            if (project.isEmpty()) {
                this.logSkip("no-project", previewTarget);
                RecentChangesOverlayRenderer.clear();
                return;
            }

            boolean debugEnabled = LumaDebugLog.enabled(project.get());
            RecentChangesOverlayRenderer.show(
                    project.get().id().toString(),
                    this.recentActions(project.get().id().toString(), previewTarget),
                    debugEnabled,
                    previewTarget
            );
        } catch (Exception exception) {
            OverlayDiagnostics.getInstance().log(
                    false,
                    "recent-coordinator-failed",
                    "recent-overlay",
                    "Coordinator failed with {}: {}",
                    exception.getClass().getSimpleName(),
                    exception.getMessage()
            );
            RecentChangesOverlayRenderer.clear();
        }
    }

    private void logSkip(String reason, PreviewTarget previewTarget) {
        OverlayDiagnostics.getInstance().log(
                false,
                "recent-coordinator-" + reason,
                "recent-overlay",
                "Coordinator skipped reason={} preview={}",
                reason,
                previewTarget
        );
    }

    private List<UndoRedoAction> recentActions(String projectId, PreviewTarget previewTarget) {
        UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
        if (previewTarget == PreviewTarget.REDO) {
            return historyManager.recentRedoActions(projectId, 10);
        }
        return historyManager.recentUndoActions(projectId, 10);
    }

    public enum PreviewTarget {
        UNDO,
        REDO
    }
}
