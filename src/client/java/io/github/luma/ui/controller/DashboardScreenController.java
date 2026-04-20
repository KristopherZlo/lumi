package io.github.luma.ui.controller;

import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.ui.state.DashboardProjectItem;
import io.github.luma.ui.state.DashboardViewState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

public final class DashboardScreenController {

    private final Minecraft client = Minecraft.getInstance();
    private final ProjectService projectService = new ProjectService();
    private final RecoveryService recoveryService = new RecoveryService();

    public DashboardViewState loadState(String status) {
        if (!this.client.hasSingleplayerServer()) {
            return new DashboardViewState(List.of(), "luma.status.singleplayer_only");
        }

        try {
            List<DashboardProjectItem> items = new ArrayList<>();
            var server = this.client.getSingleplayerServer();
            for (var project : this.projectService.listProjects(server)) {
                items.add(new DashboardProjectItem(
                        project.name(),
                        project.activeVariantId(),
                        this.projectService.loadVersions(server, project.name()).size(),
                        this.recoveryService.hasDraft(server, project.name())
                ));
            }

            return new DashboardViewState(items, status == null || status.isBlank() ? "luma.status.dashboard_ready" : status);
        } catch (IOException exception) {
            return new DashboardViewState(List.of(), "luma.status.dashboard_failed");
        }
    }
}
