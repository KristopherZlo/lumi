package io.github.luma.ui.navigation;

import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.screen.CompareScreen;
import io.github.luma.ui.screen.CreateProjectScreen;
import io.github.luma.ui.screen.DashboardScreen;
import io.github.luma.ui.screen.ProjectScreen;
import io.github.luma.ui.screen.RecoveryScreen;
import io.github.luma.ui.screen.SettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class ScreenRouter {

    private final Minecraft client = Minecraft.getInstance();
    private final ProjectScreenController projectController = new ProjectScreenController();

    public void openDashboard(Screen parent) {
        this.client.setScreen(new DashboardScreen(parent));
    }

    public void openCreateProject(Screen parent) {
        this.client.setScreen(new CreateProjectScreen(parent));
    }

    public void openProject(Screen parent, String projectName) {
        if (this.projectController.hasRecoveryDraft(projectName)) {
            this.client.setScreen(new RecoveryScreen(parent, projectName));
            return;
        }

        this.client.setScreen(new ProjectScreen(parent, projectName));
    }

    public void openProjectIgnoringRecovery(Screen parent, String projectName) {
        this.client.setScreen(new ProjectScreen(parent, projectName));
    }

    public void openProjectIgnoringRecovery(Screen parent, String projectName, String statusKey) {
        this.client.setScreen(new ProjectScreen(parent, projectName, statusKey));
    }

    public void openRecovery(Screen parent, String projectName) {
        this.client.setScreen(new RecoveryScreen(parent, projectName));
    }

    public void openSettings(Screen parent, String projectName) {
        this.client.setScreen(new SettingsScreen(parent, projectName));
    }

    public void openCompare(Screen parent, String projectName, String leftReference, String rightReference) {
        this.client.setScreen(new CompareScreen(parent, projectName, leftReference, rightReference));
    }
}
