package io.github.luma.ui.controller;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.telemetry.TelemetryService;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;

public final class QuickSaveScreenController {

    private final Query query;

    public QuickSaveScreenController() {
        this(new ServiceQuery());
    }

    QuickSaveScreenController(Query query) {
        this.query = query;
    }

    public String saveCurrentWorkspace(String message) {
        return this.saveCurrentWorkspace(message, List.of());
    }

    public String saveCurrentWorkspace(String message, List<String> tags) {
        String normalizedMessage = message == null ? "" : message.trim();
        if (!this.query.hasSingleplayerServer()) {
            return "luma.status.singleplayer_only";
        }

        try {
            this.query.saveCurrentWorkspace(normalizedMessage, tags == null ? List.of() : tags);
            return "luma.status.save_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Quick save request rejected", exception);
            return this.rejected("quick_save", this.illegalStateStatus(exception), exception);
        } catch (IllegalArgumentException exception) {
            LumaMod.LOGGER.warn("Quick save request rejected", exception);
            return this.rejected("quick_save", this.illegalArgumentStatus(exception), exception);
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Quick save request failed", exception);
            TelemetryService.getInstance().recordOperationRejected("quick_save", "luma.status.operation_failed", exception);
            return "luma.status.operation_failed";
        }
    }

    public List<ProjectVersion> currentWorkspaceVersions() {
        try {
            return this.query.currentWorkspaceVersions();
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String rejected(String action, String statusKey, Exception exception) {
        TelemetryService.getInstance().recordOperationRejected(action, statusKey, exception);
        return statusKey;
    }

    private String illegalStateStatus(IllegalStateException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("admin") || message.contains("cheats")) {
            return "luma.status.admin_required";
        }
        return "luma.status.world_operation_busy";
    }

    private String illegalArgumentStatus(IllegalArgumentException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("no pending tracked changes")) {
            return "luma.status.no_changes_to_save";
        }
        return "luma.status.operation_failed";
    }

    interface Query {

        boolean hasSingleplayerServer();

        void saveCurrentWorkspace(String message, List<String> tags) throws Exception;

        List<ProjectVersion> currentWorkspaceVersions() throws Exception;
    }

    private static final class ServiceQuery implements Query {

        private final Minecraft client = Minecraft.getInstance();
        private final ProjectService projectService = new ProjectService();
        private final VersionService versionService = new VersionService();

        @Override
        public boolean hasSingleplayerServer() {
            return this.client.hasSingleplayerServer();
        }

        @Override
        public void saveCurrentWorkspace(String message, List<String> tags) throws IOException {
            Workspace workspace = this.currentWorkspace();
            this.versionService.startSaveVersion(workspace.level(), workspace.project().name(), message, this.client.getUser().getName(), tags);
        }

        @Override
        public List<ProjectVersion> currentWorkspaceVersions() throws IOException {
            Workspace workspace = this.currentWorkspace();
            return this.projectService.loadVersions(workspace.level().getServer(), workspace.project().name());
        }

        private Workspace currentWorkspace() throws IOException {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            ServerLevel level = server.getLevel(this.client.level == null
                    ? net.minecraft.world.level.Level.OVERWORLD
                    : this.client.level.dimension());
            if (level == null) {
                level = server.overworld();
            }

            String author = this.client.getUser().getName();
            BuildProject project = this.projectService.ensureWorldProject(level, author);
            return new Workspace(level, project);
        }

        private record Workspace(ServerLevel level, BuildProject project) {
        }
    }
}
