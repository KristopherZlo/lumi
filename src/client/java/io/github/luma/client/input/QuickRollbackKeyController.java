package io.github.luma.client.input;

import io.github.luma.client.selection.LumiRegionSelectionController;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.QuickRollbackService;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.controller.ClientProjectAccess;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * Handles key-driven quick rollback requests.
 */
public final class QuickRollbackKeyController {

    private final ProjectService projectService = new ProjectService();
    private final QuickRollbackService quickRollbackService = new QuickRollbackService();

    public void quickRollback(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        try {
            ServerLevel level = this.currentLevel(client);
            var project = this.projectService.findWorldProject(level)
                    .orElseThrow(() -> new IllegalArgumentException("No active Lumi workspace in this dimension"));
            ClientProjectAccess.requireProjectAccess(client, project);
            Optional<Bounds3i> selectedBounds = LumiRegionSelectionController.getInstance().selectedBounds(
                    project.name(),
                    project.dimensionId()
            );
            if (selectedBounds.isPresent()) {
                this.quickRollbackService.quickRollback(level, project.name(), selectedBounds.get());
                client.gui.setOverlayMessage(ActionBarMessagePresenter.info("luma.status.quick_rollback_selected_started"), false);
            } else {
                this.quickRollbackService.quickRollback(level, project.name());
                client.gui.setOverlayMessage(ActionBarMessagePresenter.info("luma.status.quick_rollback_started"), false);
            }
        } catch (Exception exception) {
            client.gui.setOverlayMessage(this.statusMessage(this.statusKey(exception)), false);
        }
    }

    private ServerLevel currentLevel(Minecraft client) {
        var server = ClientProjectAccess.requireSingleplayerServer(client);
        ServerLevel level = server.getLevel(client.level.dimension());
        return level == null ? server.overworld() : level;
    }

    private String statusKey(Exception exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("admin permissions") || message.contains("cheats enabled")) {
            return "luma.status.admin_required";
        }
        if (message.contains("disabled for survival mode")) {
            return "luma.status.survival_disabled";
        }
        if (message.contains("Another world operation is already running")) {
            return "luma.status.world_operation_busy";
        }
        if (message.contains("No pending tracked changes")
                || message.contains("not based on the current branch head")
                || message.contains("no committed head")
                || message.contains("No active Lumi workspace")) {
            return "luma.status.quick_rollback_unavailable";
        }
        return "luma.status.operation_failed";
    }

    private Component statusMessage(String key) {
        if ("luma.status.operation_failed".equals(key)
                || "luma.status.world_operation_busy".equals(key)
                || "luma.status.admin_required".equals(key)
                || "luma.status.survival_disabled".equals(key)) {
            return ActionBarMessagePresenter.error(key);
        }
        return ActionBarMessagePresenter.warning(key);
    }
}
