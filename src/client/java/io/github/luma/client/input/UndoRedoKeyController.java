package io.github.luma.client.input;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.UndoRedoService;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.controller.ClientProjectAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** Starts Lumi undo/redo from the client shortcut. */
public final class UndoRedoKeyController {

    private final ProjectService projects = new ProjectService();
    private final UndoRedoService undoRedo = new UndoRedoService();
    private Intent pendingIntent;

    public void undo(Minecraft client) {
        this.start(client, Intent.UNDO);
    }

    public void redo(Minecraft client) {
        this.start(client, Intent.REDO);
    }

    public void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            this.pendingIntent = null;
            return;
        }
        if (this.pendingIntent == null) {
            return;
        }
        Intent intent = this.pendingIntent;
        this.pendingIntent = null;
        this.start(client, intent);
    }

    private void start(Minecraft client, Intent intent) {
        if (client == null || client.player == null || client.level == null || client.gui == null) {
            return;
        }
        boolean undo = intent == Intent.UNDO;
        try {
            ServerLevel level = this.currentLevel(client);
            BuildProject project = this.projects.findWorldProject(level)
                    .orElseThrow(() -> new IllegalArgumentException("No active Lumi workspace in this dimension"));
            ClientProjectAccess.requireProjectAccess(client, project);
            String actor = client.player.getName().getString();
            if (undo) {
                this.undoRedo.undo(level, project.name(), actor);
            } else {
                this.undoRedo.redo(level, project.name(), actor);
            }
            client.gui.setOverlayMessage(ActionBarMessagePresenter.info(
                    undo ? "luma.status.undo_started" : "luma.status.redo_started"
            ), false);
        } catch (Exception exception) {
            if (isStabilizationPending(exception)) {
                this.pendingIntent = intent;
                client.gui.setOverlayMessage(
                        ActionBarMessagePresenter.info("luma.status.undo_redo_settling"),
                        false
                );
                return;
            }
            if (!isExpectedFailure(exception)) {
                LumaMod.LOGGER.error("Failed to start client {}", undo ? "undo" : "redo", exception);
            }
            client.gui.setOverlayMessage(this.failureMessage(exception, undo), false);
        }
    }

    private static boolean isStabilizationPending(Exception exception) {
        return exception != null
                && exception.getMessage() != null
                && exception.getMessage().contains("still settling");
    }

    private static boolean isExpectedFailure(Exception exception) {
        String message = exception == null || exception.getMessage() == null ? "" : exception.getMessage();
        return message.contains("No Lumi action")
                || message.contains("No active Lumi workspace")
                || message.contains("Another world operation")
                || message.contains("admin permissions")
                || message.contains("cheats enabled")
                || message.contains("disabled for survival mode");
    }

    private ServerLevel currentLevel(Minecraft client) {
        var server = ClientProjectAccess.requireSingleplayerServer(client);
        ServerLevel level = server.getLevel(client.level.dimension());
        return level == null ? server.overworld() : level;
    }

    private Component failureMessage(Exception exception, boolean undo) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("No Lumi action") || message.contains("No active Lumi workspace")) {
            return ActionBarMessagePresenter.warning(
                    undo ? "luma.status.undo_unavailable" : "luma.status.redo_unavailable"
            );
        }
        if (message.contains("Another world operation")) {
            return ActionBarMessagePresenter.error("luma.status.world_operation_busy");
        }
        if (message.contains("admin permissions") || message.contains("cheats enabled")) {
            return ActionBarMessagePresenter.error("luma.status.admin_required");
        }
        if (message.contains("disabled for survival mode")) {
            return ActionBarMessagePresenter.error("luma.status.survival_disabled");
        }
        return ActionBarMessagePresenter.error("luma.status.operation_failed");
    }

    private enum Intent {
        UNDO,
        REDO
    }
}
