package io.github.luma.ui.navigation;
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

    public void openDashboard(Screen parent) {
        this.client.setScreen(new DashboardScreen(parent));
    }

    public void openCreateProject(Screen parent) {
        this.client.setScreen(new CreateProjectScreen(parent));
    }

    public void openProject(Screen parent, String projectName) {
        this.client.setScreen(new ProjectScreen(parent, projectName));
    }

    public void openProject(Screen parent, String projectName, String variantId) {
        this.client.setScreen(new ProjectScreen(parent, projectName, variantId, "luma.status.project_ready"));
    }

    public void openProjectIgnoringRecovery(Screen parent, String projectName) {
        this.client.setScreen(new ProjectScreen(parent, projectName));
    }

    public void openProjectIgnoringRecovery(Screen parent, String projectName, String statusKey) {
        this.client.setScreen(new ProjectScreen(parent, projectName, statusKey));
    }

    public void openProjectIgnoringRecovery(Screen parent, String projectName, String variantId, String statusKey) {
        this.client.setScreen(new ProjectScreen(parent, projectName, variantId, statusKey));
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

    public void openCompare(
            Screen parent,
            String projectName,
            String leftReference,
            String rightReference,
            String contextVersionId
    ) {
        this.client.setScreen(new CompareScreen(parent, projectName, leftReference, rightReference, contextVersionId));
    }
}
