package io.github.luma.client.input;

import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.QuickRollbackService;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.controller.ClientProjectAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * Handles key-driven quick rollback and return-before-restore requests.
 */
public final class QuickRollbackKeyController {

    private final ProjectService projectService = new ProjectService();
    private final QuickRollbackService quickRollbackService = new QuickRollbackService();

    public void quickRollback(Minecraft client) {
        this.start(client, false);
    }

    public void returnBeforeRestore(Minecraft client) {
        this.start(client, true);
    }

    private void start(Minecraft client, boolean returnBeforeRestore) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        try {
            ServerLevel level = this.currentLevel(client);
            var project = this.projectService.findWorldProject(level)
                    .orElseThrow(() -> new IllegalArgumentException("No active Lumi workspace in this dimension"));
            if (returnBeforeRestore) {
                this.quickRollbackService.returnBeforeLastRestore(level, project.name());
            } else {
                this.quickRollbackService.quickRollback(level, project.name());
            }
            client.gui.setOverlayMessage(ActionBarMessagePresenter.info(
                    returnBeforeRestore
                            ? "luma.status.return_before_restore_started"
                            : "luma.status.quick_rollback_started"
            ), false);
        } catch (Exception exception) {
            client.gui.setOverlayMessage(this.statusMessage(this.statusKey(exception, returnBeforeRestore)), false);
        }
    }

    private ServerLevel currentLevel(Minecraft client) {
        var server = ClientProjectAccess.requireSingleplayerServer(client);
        ServerLevel level = server.getLevel(client.level.dimension());
        return level == null ? server.overworld() : level;
    }

    private String statusKey(Exception exception, boolean returnBeforeRestore) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("admin permissions") || message.contains("cheats enabled")) {
            return "luma.status.admin_required";
        }
        if (message.contains("Another world operation is already running")) {
            return "luma.status.world_operation_busy";
        }
        if (message.contains("No restore return point")) {
            return "luma.status.return_before_restore_unavailable";
        }
        if (message.contains("no committed head") || message.contains("No active Lumi workspace")) {
            return returnBeforeRestore
                    ? "luma.status.return_before_restore_unavailable"
                    : "luma.status.quick_rollback_unavailable";
        }
        return "luma.status.operation_failed";
    }

    private Component statusMessage(String key) {
        if ("luma.status.operation_failed".equals(key)
                || "luma.status.world_operation_busy".equals(key)
                || "luma.status.admin_required".equals(key)) {
            return ActionBarMessagePresenter.error(key);
        }
        return ActionBarMessagePresenter.warning(key);
    }
}
