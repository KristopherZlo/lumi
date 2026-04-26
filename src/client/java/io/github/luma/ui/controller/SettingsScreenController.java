package io.github.luma.ui.controller;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectIntegrityReport;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.domain.service.ProjectIntegrityService;
import io.github.luma.domain.service.ProjectService;
import java.util.List;
import net.minecraft.client.Minecraft;

public final class SettingsScreenController {

    private final Minecraft client = Minecraft.getInstance();
    private final ProjectService projectService = new ProjectService();
    private final ProjectIntegrityService integrityService = new ProjectIntegrityService();

    public BuildProject loadProject(String projectName) {
        try {
            return this.projectService.loadProject(ClientProjectAccess.requireSingleplayerServer(this.client), projectName);
        } catch (Exception exception) {
            return null;
        }
    }

    public ProjectIntegrityReport loadIntegrity(String projectName) {
        try {
            return this.integrityService.inspect(ClientProjectAccess.requireSingleplayerServer(this.client), projectName);
        } catch (Exception exception) {
            return new ProjectIntegrityReport(false, List.of(), List.of("inspect-failed"));
        }
    }

    public String saveSettings(String projectName, ProjectSettings settings) {
        try {
            this.projectService.updateSettings(ClientProjectAccess.requireSingleplayerServer(this.client), projectName, settings);
            return "luma.status.settings_saved";
        } catch (Exception exception) {
            return "luma.status.operation_failed";
        }
    }

    public String saveAll(String projectName, ProjectSettings settings, boolean archived) {
        try {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            this.projectService.updateSettings(server, projectName, settings);
            this.projectService.setArchived(server, projectName, archived);
            return "luma.status.settings_saved";
        } catch (Exception exception) {
            return "luma.status.operation_failed";
        }
    }

    public String setArchived(String projectName, boolean archived) {
        try {
            this.projectService.setArchived(ClientProjectAccess.requireSingleplayerServer(this.client), projectName, archived);
            return archived ? "luma.status.archive_enabled" : "luma.status.archive_disabled";
        } catch (Exception exception) {
            return "luma.status.operation_failed";
        }
    }
}
