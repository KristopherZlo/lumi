package io.github.luma.client.input;

import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.UndoRedoService;
import io.github.luma.ui.controller.ClientProjectAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * Handles client key-driven undo and redo requests.
 */
public final class UndoRedoKeyController {

    private final ProjectService projectService = new ProjectService();
    private final UndoRedoService undoRedoService = new UndoRedoService();

    public void undo(Minecraft client) {
        this.start(client, true);
    }

    public void redo(Minecraft client) {
        this.start(client, false);
    }

    private void start(Minecraft client, boolean undo) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }

        try {
            ServerLevel level = this.currentLevel(client);
            var project = this.projectService.findWorldProject(level)
                    .orElseThrow(() -> new IllegalArgumentException("No active Lumi workspace in this dimension"));
            if (undo) {
                this.undoRedoService.undo(level, project.name());
            } else {
                this.undoRedoService.redo(level, project.name());
            }
            client.gui.setOverlayMessage(Component.translatable(
                    undo ? "luma.status.undo_started" : "luma.status.redo_started"
            ), false);
        } catch (Exception exception) {
            client.gui.setOverlayMessage(Component.translatable(this.statusKey(exception, undo)), false);
        }
    }

    private ServerLevel currentLevel(Minecraft client) {
        var server = ClientProjectAccess.requireSingleplayerServer(client);
        ServerLevel level = server.getLevel(client.level.dimension());
        return level == null ? server.overworld() : level;
    }

    private String statusKey(Exception exception, boolean undo) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("admin permissions") || message.contains("cheats enabled")) {
            return "luma.status.admin_required";
        }
        if (message.contains("Another world operation is already running")) {
            return "luma.status.world_operation_busy";
        }
        if (message.contains("No Lumi action") || message.contains("No active Lumi workspace")) {
            return undo ? "luma.status.undo_unavailable" : "luma.status.redo_unavailable";
        }
        return "luma.status.operation_failed";
    }
}
