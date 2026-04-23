package io.github.luma.ui.overlay;

import io.github.luma.domain.service.ProjectService;
import io.github.luma.ui.controller.ClientProjectAccess;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
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
                    UndoRedoHistoryManager.getInstance().recentUndoActions(project.get().id().toString(), 10)
            );
        } catch (Exception exception) {
            RecentChangesOverlayRenderer.clear();
        }
    }
}
