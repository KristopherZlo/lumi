package io.github.luma.ui.controller;

import io.github.luma.domain.service.ProjectService;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class ClientProjectAccess {

    private ClientProjectAccess() {
    }

    public static MinecraftServer requireSingleplayerServer(Minecraft client) {
        if (!client.hasSingleplayerServer() || client.getSingleplayerServer() == null) {
            throw new IllegalStateException("Lumi requires an integrated singleplayer server");
        }

        return client.getSingleplayerServer();
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
}
