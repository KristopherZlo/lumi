package io.github.luma.ui.controller;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import java.io.IOException;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ClientProjectAccess {

    public static final String SURVIVAL_DISABLED_MESSAGE = "Lumi is disabled for survival mode";

    private ClientProjectAccess() {
    }

    public static MinecraftServer requireSingleplayerServer(Minecraft client) {
        if (!client.hasSingleplayerServer() || client.getSingleplayerServer() == null) {
            throw new IllegalStateException("Lumi requires an integrated singleplayer server");
        }
        if (currentServerPlayer(client) == null || !LumaAccessControl.getInstance().canUse(currentServerPlayer(client))) {
            throw new IllegalStateException("Lumi requires admin permissions or cheats enabled");
        }

        return client.getSingleplayerServer();
    }

    public static BuildProject ensureCurrentWorldProject(
            Minecraft client,
            ProjectService projectService,
            ServerLevel level,
            String author
    ) throws IOException {
        Optional<BuildProject> existing = projectService.findWorldProject(level);
        if (existing.isPresent()) {
            requireProjectAccess(client, existing.get());
            return existing.get();
        }
        requireProjectCreationAccess(client);
        return projectService.ensureWorldProject(level, author);
    }

    public static Optional<BuildProject> findCurrentWorldProject(Minecraft client) throws IOException {
        var server = requireSingleplayerServer(client);
        ServerLevel level = server.getLevel(client.level == null ? net.minecraft.world.level.Level.OVERWORLD : client.level.dimension());
        if (level == null) {
            level = server.overworld();
        }
        Optional<BuildProject> project = HistoryCaptureManager.getInstance().findWholeDimensionProject(level);
        if (project.isEmpty() || !canUseProject(client, project.get())) {
            return Optional.empty();
        }
        return project;
    }

    public static ServerLevel resolveProjectLevel(Minecraft client, ProjectService projectService, String projectName) throws IOException {
        var server = requireSingleplayerServer(client);
        var project = projectService.loadProject(server, projectName);
        requireProjectAccess(client, project);

        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(project.dimensionId())) {
                return level;
            }
        }

        throw new IllegalArgumentException("Project dimension is not available: " + project.dimensionId());
    }

    public static void requireProjectAccess(Minecraft client, BuildProject project) {
        ServerPlayer player = currentServerPlayer(client);
        if (!LumaAccessControl.getInstance().canUse(player, project == null ? null : project.settings())) {
            throw disabledReason(player, project == null ? null : project.settings());
        }
    }

    public static boolean canUseProject(Minecraft client, BuildProject project) {
        ServerPlayer player = currentServerPlayer(client);
        return LumaAccessControl.getInstance().canUse(player, project == null ? null : project.settings());
    }

    public static boolean survivalModeDisabled(Minecraft client, BuildProject project) {
        return LumaAccessControl.getInstance().survivalModeDisabled(
                currentServerPlayer(client),
                project == null ? null : project.settings()
        );
    }

    private static void requireProjectCreationAccess(Minecraft client) {
        ServerPlayer player = currentServerPlayer(client);
        if (!LumaAccessControl.getInstance().canUse(player, ProjectSettings.defaults())) {
            throw disabledReason(player, ProjectSettings.defaults());
        }
    }

    private static IllegalStateException disabledReason(ServerPlayer player, ProjectSettings settings) {
        if (LumaAccessControl.getInstance().survivalModeDisabled(player, settings)) {
            return new IllegalStateException(SURVIVAL_DISABLED_MESSAGE);
        }
        return new IllegalStateException("Lumi requires admin permissions or cheats enabled");
    }

    private static ServerPlayer currentServerPlayer(Minecraft client) {
        if (client == null || client.player == null || client.getSingleplayerServer() == null) {
            return null;
        }
        return client.getSingleplayerServer().getPlayerList().getPlayer(client.player.getUUID());
    }
}
