package io.github.luma.ui.controller;

import io.github.luma.LumaMod;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.ui.screen.ProjectOpeningScreen;
import io.github.luma.ui.screen.ProjectScreen;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Opens the current singleplayer workspace without blocking the client tick.
 */
public final class ClientWorkspaceOpenService {

    private final ProjectService projectService;
    private final AtomicReference<CompletableFuture<String>> pendingOpen = new AtomicReference<>();

    public ClientWorkspaceOpenService() {
        this(new ProjectService());
    }

    ClientWorkspaceOpenService(ProjectService projectService) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
    }

    public void openCurrentWorkspace(Minecraft client, Screen parent) {
        if (client == null || client.player == null) {
            return;
        }

        MinecraftServer server;
        try {
            server = ClientProjectAccess.requireSingleplayerServer(client);
        } catch (IllegalStateException exception) {
            client.gui.setOverlayMessage(Component.translatable("luma.status.admin_required"), false);
            return;
        }

        CompletableFuture<String> request = new CompletableFuture<>();
        CompletableFuture<String> previous = this.pendingOpen.getAndSet(request);
        if (previous != null) {
            previous.cancel(false);
        }

        ResourceKey<Level> dimension = client.level == null ? Level.OVERWORLD : client.level.dimension();
        String author = client.getUser().getName();
        client.setScreen(new ProjectOpeningScreen(parent));

        server.execute(() -> this.ensureWorkspace(server, dimension, author, request));
        request.whenComplete((projectName, throwable) -> client.execute(() -> this.completeOpen(client, parent, request, projectName, throwable)));
    }

    private void ensureWorkspace(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            String author,
            CompletableFuture<String> request
    ) {
        if (request.isCancelled()) {
            return;
        }

        try {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                level = server.overworld();
            }
            request.complete(this.projectService.ensureWorldProject(level, author).name());
        } catch (Throwable throwable) {
            request.completeExceptionally(throwable);
        }
    }

    private void completeOpen(
            Minecraft client,
            Screen parent,
            CompletableFuture<String> request,
            String projectName,
            Throwable throwable
    ) {
        if (!this.pendingOpen.compareAndSet(request, null) || request.isCancelled()) {
            return;
        }

        if (throwable != null) {
            LumaMod.LOGGER.warn("Failed to open current Lumi workspace", throwable);
            client.gui.setOverlayMessage(Component.translatable("luma.status.project_open_failed"), false);
            client.setScreen(parent);
            return;
        }

        client.setScreen(new ProjectScreen(parent, projectName));
    }
}
