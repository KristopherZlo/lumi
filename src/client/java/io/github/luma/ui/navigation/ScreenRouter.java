package io.github.luma.ui.navigation;
import io.github.luma.ui.screen.CompareScreen;
import io.github.luma.ui.screen.CreateProjectScreen;
import io.github.luma.ui.screen.DashboardScreen;
import io.github.luma.ui.screen.ProjectScreen;
import io.github.luma.ui.screen.RecoveryScreen;
import io.github.luma.ui.screen.SaveDetailsScreen;
import io.github.luma.ui.screen.SaveScreen;
import io.github.luma.ui.screen.SettingsScreen;
import io.github.luma.ui.screen.ShareScreen;
import io.github.luma.ui.screen.VariantsScreen;
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

    public void openSave(Screen parent, String projectName) {
        this.client.setScreen(new SaveScreen(parent, projectName));
    }

    public void openSave(Screen parent, String projectName, String initialMessage, boolean showMoreOptions) {
        this.client.setScreen(new SaveScreen(parent, projectName, initialMessage, showMoreOptions));
    }

    public void openSaveDetails(Screen parent, String projectName, String versionId) {
        this.client.setScreen(new SaveDetailsScreen(parent, projectName, versionId));
    }

    public void openSettings(Screen parent, String projectName) {
        this.client.setScreen(new SettingsScreen(parent, projectName));
    }

    public void openVariants(Screen parent, String projectName) {
        this.client.setScreen(new VariantsScreen(parent, projectName));
    }

    public void openVariants(Screen parent, String projectName, String baseVersionId) {
        this.client.setScreen(new VariantsScreen(parent, projectName, baseVersionId));
    }

    public void openShare(Screen parent, String projectName) {
        this.client.setScreen(new ShareScreen(parent, projectName));
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
