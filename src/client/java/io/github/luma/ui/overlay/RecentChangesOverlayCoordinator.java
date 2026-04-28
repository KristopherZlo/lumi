package io.github.luma.ui.overlay;

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
        if (client == null || client.player == null || client.level == null || !altHeld || CompareOverlayRenderer.visible()) {
            RecentChangesOverlayRenderer.clear();
            return;
        }

        try {
            var project = ClientProjectAccess.findCurrentWorldProject(client, this.projectService);
            if (project.isEmpty()) {
                RecentChangesOverlayRenderer.clear();
                return;
            }

            RecentChangesOverlayRenderer.show(
                    project.get().id().toString(),
                    this.recentActions(project.get().id().toString(), previewTarget)
            );
        } catch (Exception exception) {
            RecentChangesOverlayRenderer.clear();
        }
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
