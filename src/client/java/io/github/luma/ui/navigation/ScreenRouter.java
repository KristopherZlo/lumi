package io.github.luma.ui.navigation;

import io.github.luma.ui.screen.LdLib2Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class ScreenRouter {

    private final Minecraft client = Minecraft.getInstance();

    public void openDashboard(Screen parent) {
        this.client.setScreen(LdLib2Screens.dashboard(parent));
    }

    public void openCreateProject(Screen parent) {
        this.client.setScreen(LdLib2Screens.createProject(parent));
    }

    public void openProject(Screen parent, String projectName) {
        this.client.setScreen(LdLib2Screens.project(parent, projectName));
    }

    public void openProject(Screen parent, String projectName, String variantId) {
        this.client.setScreen(LdLib2Screens.project(parent, projectName, variantId, "luma.status.project_ready"));
    }

    public void openProjectIgnoringRecovery(Screen parent, String projectName) {
        this.client.setScreen(LdLib2Screens.project(parent, projectName));
    }

    public void openProjectIgnoringRecovery(Screen parent, String projectName, String statusKey) {
        this.client.setScreen(LdLib2Screens.project(parent, projectName, statusKey));
    }

    public void openProjectIgnoringRecovery(Screen parent, String projectName, String variantId, String statusKey) {
        this.client.setScreen(LdLib2Screens.project(parent, projectName, variantId, statusKey));
    }

    public void openRecovery(Screen parent, String projectName) {
        this.client.setScreen(LdLib2Screens.recovery(parent, projectName));
    }

    public void openSave(Screen parent, String projectName) {
        this.client.setScreen(LdLib2Screens.save(parent, projectName));
    }

    public void openSave(Screen parent, String projectName, String initialMessage, boolean showMoreOptions) {
        this.client.setScreen(LdLib2Screens.save(parent, projectName, initialMessage, showMoreOptions));
    }

    public void openSaveDetails(Screen parent, String projectName, String versionId) {
        this.client.setScreen(LdLib2Screens.saveDetails(parent, projectName, versionId));
    }

    public void openSettings(Screen parent, String projectName) {
        this.client.setScreen(LdLib2Screens.settings(parent, projectName));
    }

    public void openMore(Screen parent, String projectName) {
        this.client.setScreen(LdLib2Screens.more(parent, projectName));
    }

    public void openDiagnostics(Screen parent, String projectName) {
        this.client.setScreen(LdLib2Screens.diagnostics(parent, projectName));
    }

    public void openCleanup(Screen parent, String projectName) {
        this.client.setScreen(LdLib2Screens.cleanup(parent, projectName));
    }

    public void openAdvanced(Screen parent, String projectName) {
        this.client.setScreen(LdLib2Screens.advanced(parent, projectName));
    }

    public void openVariants(Screen parent, String projectName) {
        this.client.setScreen(LdLib2Screens.variants(parent, projectName));
    }

    public void openVariants(Screen parent, String projectName, String baseVersionId) {
        this.client.setScreen(LdLib2Screens.variants(parent, projectName, baseVersionId));
    }

    public void openShare(Screen parent, String projectName) {
        this.client.setScreen(LdLib2Screens.share(parent, projectName));
    }

    public void openCompare(Screen parent, String projectName, String leftReference, String rightReference) {
        this.client.setScreen(LdLib2Screens.compare(parent, projectName, leftReference, rightReference));
    }

    public void openCompare(
            Screen parent,
            String projectName,
            String leftReference,
            String rightReference,
            String contextVersionId
    ) {
        this.client.setScreen(LdLib2Screens.compare(parent, projectName, leftReference, rightReference, contextVersionId));
    }
}
