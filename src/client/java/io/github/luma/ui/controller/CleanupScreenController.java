package io.github.luma.ui.controller;

import io.github.luma.domain.model.ProjectCleanupReport;
import io.github.luma.domain.service.ProjectCleanupService;
import net.minecraft.client.Minecraft;

public final class CleanupScreenController {

    private final Minecraft client = Minecraft.getInstance();
    private final ProjectCleanupService cleanupService = new ProjectCleanupService();

    public ProjectCleanupReport inspect(String projectName) {
        try {
            return this.cleanupService.inspect(ClientProjectAccess.requireSingleplayerServer(this.client), projectName);
        } catch (Exception exception) {
            return null;
        }
    }

    public ProjectCleanupReport apply(String projectName) {
        try {
            return this.cleanupService.apply(ClientProjectAccess.requireSingleplayerServer(this.client), projectName);
        } catch (Exception exception) {
            return null;
        }
    }
}
