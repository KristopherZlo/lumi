package io.github.luma.ui.controller;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.VersionService;
import java.io.IOException;
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
        String normalizedMessage = message == null ? "" : message.trim();
        if (normalizedMessage.isBlank()) {
            return "luma.status.quick_save_name_required";
        }
        if (!this.query.hasSingleplayerServer()) {
            return "luma.status.singleplayer_only";
        }

        try {
            this.query.saveCurrentWorkspace(normalizedMessage);
            return "luma.status.save_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Quick save request rejected", exception);
            return this.illegalStateStatus(exception);
        } catch (IllegalArgumentException exception) {
            LumaMod.LOGGER.warn("Quick save request rejected", exception);
            return this.illegalArgumentStatus(exception);
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Quick save request failed", exception);
            return "luma.status.operation_failed";
        }
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

        void saveCurrentWorkspace(String message) throws Exception;
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
        public void saveCurrentWorkspace(String message) throws IOException {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            ServerLevel level = server.getLevel(this.client.level == null
                    ? net.minecraft.world.level.Level.OVERWORLD
                    : this.client.level.dimension());
            if (level == null) {
                level = server.overworld();
            }

            String author = this.client.getUser().getName();
            BuildProject project = this.projectService.ensureWorldProject(level, author);
            this.versionService.startSaveVersion(level, project.name(), message, author);
        }
    }
}
