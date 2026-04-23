package io.github.luma.ui.controller;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.minecraft.access.LumaAccessControl;
import java.io.IOException;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ClientProjectAccess {

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

    public static Optional<BuildProject> findCurrentWorldProject(Minecraft client, ProjectService projectService) throws IOException {
        var server = requireSingleplayerServer(client);
        ServerLevel level = server.getLevel(client.level == null ? net.minecraft.world.level.Level.OVERWORLD : client.level.dimension());
        if (level == null) {
            level = server.overworld();
        }
        return projectService.findWorldProject(level);
    }

    public static ServerLevel resolveProjectLevel(Minecraft client, ProjectService projectService, String projectName) throws IOException {
        var server = requireSingleplayerServer(client);
        var project = projectService.loadProject(server, projectName);

        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(project.dimensionId())) {
                return level;
            }
        }

        throw new IllegalArgumentException("Project dimension is not available: " + project.dimensionId());
    }

    private static ServerPlayer currentServerPlayer(Minecraft client) {
        if (client == null || client.player == null || client.getSingleplayerServer() == null) {
            return null;
        }
        return client.getSingleplayerServer().getPlayerList().getPlayer(client.player.getUUID());
    }
}
