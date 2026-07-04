package io.github.luma.ui.controller;

import io.github.luma.LumaMod;
import io.github.luma.client.onboarding.ClientOnboardingService;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.client.update.UpdatePromptCoordinator;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.domain.service.WorkZoneService;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.screen.OnboardingScreen;
import io.github.luma.ui.screen.ProjectOpeningScreen;
import io.github.luma.ui.screen.ProjectScreen;
import io.github.luma.ui.screen.RecoveryScreen;
import io.github.luma.ui.screen.WorkZoneScreen;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Opens the current workspace without blocking the client tick.
 */
public final class ClientWorkspaceOpenService {

    private final ProjectService projectService;
    private final RecoveryService recoveryService;
    private final WorkZoneService workZoneService;
    private final ClientOnboardingService onboardingService;
    private final UpdatePromptCoordinator updatePromptCoordinator = new UpdatePromptCoordinator();
    private final AtomicReference<CompletableFuture<WorkspaceOpenResult>> pendingOpen = new AtomicReference<>();

    public ClientWorkspaceOpenService() {
        this(new ProjectService(), new RecoveryService(), new ClientOnboardingService(), new WorkZoneService());
    }

    ClientWorkspaceOpenService(
            ProjectService projectService,
            RecoveryService recoveryService,
            ClientOnboardingService onboardingService,
            WorkZoneService workZoneService
    ) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.recoveryService = Objects.requireNonNull(recoveryService, "recoveryService");
        this.onboardingService = Objects.requireNonNull(onboardingService, "onboardingService");
        this.workZoneService = Objects.requireNonNull(workZoneService, "workZoneService");
    }

    public void openCurrentWorkspace(Minecraft client, Screen parent) {
        this.openCurrentWorkspace(client, parent, WorkspaceOpenTarget.PROJECT);
    }

    public void openCurrentWorkspaceOnboarding(Minecraft client, Screen parent) {
        this.openCurrentWorkspace(client, parent, WorkspaceOpenTarget.ONBOARDING);
    }

    public boolean rejectIfSurvivalDisabled(Minecraft client) {
        if (client == null || client.player == null || client.level == null || !client.hasSingleplayerServer()) {
            return false;
        }
        try {
            if (client.getSingleplayerServer() == null) {
                return false;
            }
            ServerLevel level = client.getSingleplayerServer().getLevel(client.level.dimension());
            if (level == null) {
                level = client.getSingleplayerServer().overworld();
            }
            Optional<BuildProject> project = this.projectService.findWorldProject(level);
            if (ClientProjectAccess.survivalModeDisabled(client, project.orElse(null))) {
                this.notifySurvivalDisabled(client);
                return true;
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    public void notifySurvivalDisabled(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }
        Component message = Component.translatable("luma.status.survival_disabled");
        client.player.displayClientMessage(message, true);
        client.player.displayClientMessage(message, false);
    }

    private void openCurrentWorkspace(Minecraft client, Screen parent, WorkspaceOpenTarget target) {
        if (client == null || client.player == null) {
            return;
        }
        if (!client.hasSingleplayerServer()) {
            client.setScreen(new WorkZoneScreen(parent, ""));
            return;
        }
        if (this.rejectIfSurvivalDisabled(client)) {
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
        UUID playerId = client.player.getUUID();
        client.setScreen(new ProjectOpeningScreen(parent));

        server.execute(() -> this.ensureWorkspace(server, dimension, author, playerId, request));
        request.whenComplete((projectName, throwable) ->
                client.execute(() -> this.completeOpen(client, parent, request, target, projectName, throwable)));
    }

    private void ensureWorkspace(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            String author,
            UUID playerId,
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
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            Optional<BuildProject> existing = this.projectService.findWorldProject(level);
            ProjectSettings settings = existing.map(BuildProject::settings).orElse(ProjectSettings.defaults());
            if (!LumaAccessControl.getInstance().canUse(player, settings)) {
                throw this.disabledReason(player, settings);
            }
            BuildProject project = existing.orElse(null);
            if (project == null) {
                project = this.projectService.ensureWorldProject(level, author);
            }
            boolean hasActiveZone = this.workZoneService.activeZone(
                    this.projectService.resolveLayout(server, project.name()),
                    author
            ).isPresent();
            request.complete(new WorkspaceOpenResult(
                    project.name(),
                    this.recoveryService.hasInterruptedDraft(server, project.name()),
                    hasActiveZone
            ));
        } catch (Throwable throwable) {
            request.completeExceptionally(throwable);
        }
    }

    private void completeOpen(
            Minecraft client,
            Screen parent,
            CompletableFuture<WorkspaceOpenResult> request,
            WorkspaceOpenTarget target,
            WorkspaceOpenResult result,
            Throwable throwable
    ) {
        if (!this.pendingOpen.compareAndSet(request, null) || request.isCancelled()) {
            return;
        }

        if (throwable != null) {
            if (this.isSurvivalDisabled(throwable)) {
                this.notifySurvivalDisabled(client);
                client.setScreen(parent);
                return;
            }
            LumaMod.LOGGER.warn("Failed to open current Lumi workspace", throwable);
            client.gui.setOverlayMessage(ActionBarMessagePresenter.error("luma.status.project_open_failed"), false);
            client.setScreen(parent);
            return;
        }

        if (result.hasRecoveryDraft()) {
            client.setScreen(new RecoveryScreen(parent, result.projectName()));
            return;
        }
        if (target == WorkspaceOpenTarget.PROJECT && result.hasActiveZone()) {
            client.setScreen(new WorkZoneScreen(parent, result.projectName()));
            return;
        }
        if (target == WorkspaceOpenTarget.ONBOARDING) {
            client.setScreen(new OnboardingScreen(parent, result.projectName(), this.onboardingService));
            return;
        }
        if (this.onboardingService.shouldShowOnboarding()) {
            client.setScreen(new OnboardingScreen(parent, result.projectName(), this.onboardingService));
            return;
        }
        ProjectScreen projectScreen = new ProjectScreen(parent, result.projectName());
        this.updatePromptCoordinator.openProjectScreen(client, projectScreen);
    }

    private enum WorkspaceOpenTarget {
        PROJECT,
        ONBOARDING
    }

    private IllegalStateException disabledReason(ServerPlayer player, ProjectSettings settings) {
        if (LumaAccessControl.getInstance().survivalModeDisabled(player, settings)) {
            return new IllegalStateException(ClientProjectAccess.SURVIVAL_DISABLED_MESSAGE);
        }
        return new IllegalStateException("Lumi requires admin permissions or cheats enabled");
    }

    private boolean isSurvivalDisabled(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (ClientProjectAccess.SURVIVAL_DISABLED_MESSAGE.equals(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record WorkspaceOpenResult(String projectName, boolean hasRecoveryDraft, boolean hasActiveZone) {
    }
}
