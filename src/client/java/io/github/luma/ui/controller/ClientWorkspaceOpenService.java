package io.github.luma.ui.controller;

import io.github.luma.LumaMod;
import io.github.luma.client.onboarding.ClientOnboardingService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.screen.OnboardingScreen;
import io.github.luma.ui.screen.ProjectOpeningScreen;
import io.github.luma.ui.screen.ProjectScreen;
import io.github.luma.ui.screen.RecoveryScreen;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Opens the current singleplayer workspace without blocking the client tick.
 */
public final class ClientWorkspaceOpenService {

    private final ProjectService projectService;
    private final RecoveryService recoveryService;
    private final ClientOnboardingService onboardingService;
    private final AtomicReference<CompletableFuture<WorkspaceOpenResult>> pendingOpen = new AtomicReference<>();

    public ClientWorkspaceOpenService() {
        this(new ProjectService(), new RecoveryService(), new ClientOnboardingService());
    }

    ClientWorkspaceOpenService(
            ProjectService projectService,
            RecoveryService recoveryService,
            ClientOnboardingService onboardingService
    ) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.recoveryService = Objects.requireNonNull(recoveryService, "recoveryService");
        this.onboardingService = Objects.requireNonNull(onboardingService, "onboardingService");
    }

    public void openCurrentWorkspace(Minecraft client, Screen parent) {
        if (client == null || client.player == null) {
            return;
        }

        MinecraftServer server;
        try {
            server = ClientProjectAccess.requireSingleplayerServer(client);
        } catch (IllegalStateException exception) {
            client.gui.setOverlayMessage(ActionBarMessagePresenter.error("luma.status.admin_required"), false);
            return;
        }

        CompletableFuture<WorkspaceOpenResult> request = new CompletableFuture<>();
        CompletableFuture<WorkspaceOpenResult> previous = this.pendingOpen.getAndSet(request);
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
            CompletableFuture<WorkspaceOpenResult> request
    ) {
        if (request.isCancelled()) {
            return;
        }

        try {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                level = server.overworld();
            }
            var project = this.projectService.ensureWorldProject(level, author);
            request.complete(new WorkspaceOpenResult(
                    project.name(),
                    this.recoveryService.hasInterruptedDraft(server, project.name())
            ));
        } catch (Throwable throwable) {
            request.completeExceptionally(throwable);
        }
    }

    private void completeOpen(
            Minecraft client,
            Screen parent,
            CompletableFuture<WorkspaceOpenResult> request,
            WorkspaceOpenResult result,
            Throwable throwable
    ) {
        if (!this.pendingOpen.compareAndSet(request, null) || request.isCancelled()) {
            return;
        }

        if (throwable != null) {
            LumaMod.LOGGER.warn("Failed to open current Lumi workspace", throwable);
            client.gui.setOverlayMessage(ActionBarMessagePresenter.error("luma.status.project_open_failed"), false);
            client.setScreen(parent);
            return;
        }

        if (result.hasRecoveryDraft()) {
            client.setScreen(new RecoveryScreen(parent, result.projectName()));
            return;
        }
        if (this.onboardingService.shouldShowOnboarding()) {
            client.setScreen(new OnboardingScreen(parent, result.projectName(), this.onboardingService));
            return;
        }
        client.setScreen(new ProjectScreen(parent, result.projectName()));
    }

    private record WorkspaceOpenResult(String projectName, boolean hasRecoveryDraft) {
    }
}
