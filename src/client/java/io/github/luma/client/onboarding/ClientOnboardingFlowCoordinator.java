package io.github.luma.client.onboarding;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.onboarding.OnboardingTour;
import io.github.luma.ui.screen.OnboardingScreen;
import io.github.luma.ui.state.ProjectHomeViewState;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Owns the no-screen part of onboarding where the player must interact with the
 * world before Lumi can continue with modal shortcut teaching.
 */
public final class ClientOnboardingFlowCoordinator {

    private static final Identifier HUD_ELEMENT_ID = Identifier.fromNamespaceAndPath(
            LumaMod.MOD_ID,
            "onboarding_world_prompt"
    );
    private static final int REFRESH_INTERVAL_TICKS = 10;
    private static final int PANEL_WIDTH = 330;
    private static final ClientOnboardingFlowCoordinator INSTANCE = new ClientOnboardingFlowCoordinator();

    private final ProjectHomeScreenController stateController = new ProjectHomeScreenController();
    private String projectName = "";
    private String variantId = "";
    private String statusKey = "luma.status.project_ready";
    private ClientOnboardingService onboardingService = new ClientOnboardingService();
    private OnboardingTour tour;
    private int baselinePendingBlocks = -1;
    private int refreshCooldown;

    private ClientOnboardingFlowCoordinator() {
    }

    public static ClientOnboardingFlowCoordinator getInstance() {
        return INSTANCE;
    }

    public void registerHud() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.OVERLAY_MESSAGE,
                HUD_ELEMENT_ID,
                this::render
        );
    }

    public void startBreakBlockStep(
            String projectName,
            String variantId,
            String statusKey,
            ClientOnboardingService onboardingService,
            OnboardingTour tour
    ) {
        this.projectName = projectName == null ? "" : projectName;
        this.variantId = variantId == null ? "" : variantId;
        this.statusKey = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
        this.onboardingService = onboardingService == null ? new ClientOnboardingService() : onboardingService;
        this.tour = tour;
        this.baselinePendingBlocks = -1;
        this.refreshCooldown = 0;
    }

    public void tick(Minecraft client) {
        if (!this.active() || client == null || client.screen != null) {
            return;
        }
        if (!"break_block".equals(this.tour.currentPageId())) {
            this.clear();
            return;
        }
        if (client.player == null || client.level == null || !client.hasSingleplayerServer()) {
            return;
        }
        if (this.refreshCooldown-- > 0) {
            return;
        }
        this.refreshCooldown = REFRESH_INTERVAL_TICKS;

        int pendingBlocks = this.pendingBlocks();
        if (this.baselinePendingBlocks < 0) {
            this.baselinePendingBlocks = pendingBlocks;
            return;
        }
        if (pendingBlocks <= this.baselinePendingBlocks) {
            return;
        }

        this.tour.advanceAfterWorldEdit();
        OnboardingTour activeTour = this.tour;
        String activeProjectName = this.projectName;
        String activeVariantId = this.variantId;
        String activeStatusKey = this.statusKey;
        ClientOnboardingService activeService = this.onboardingService;
        this.clear();
        client.setScreen(new OnboardingScreen(
                null,
                activeProjectName,
                activeVariantId,
                activeStatusKey,
                activeService,
                activeTour,
                false
        ));
    }

    private void render(GuiGraphics drawContext, net.minecraft.client.DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        if (!this.active()
                || client.options.hideGui
                || client.screen != null
                || !"break_block".equals(this.tour.currentPageId())) {
            return;
        }

        Font font = client.font;
        int x = Math.max(8, (drawContext.guiWidth() - PANEL_WIDTH) / 2);
        int y = Math.max(8, drawContext.guiHeight() - 88);
        int height = 58;
        drawContext.fill(x, y, x + PANEL_WIDTH, y + height, 0xF0171B1E);
        drawContext.renderOutline(x, y, PANEL_WIDTH, height, 0xFF3B4147);
        drawContext.drawString(font, this.tour.headerText(), x + 8, y + 8, 0xFF98A6B3, false);
        drawContext.drawString(font, this.tour.pageName(), x + 8, y + 20, 0xFF4ADE80, false);
        drawContext.drawString(font, this.tour.helpText(), x + 8, y + 34, 0xFFF3F7FA, false);
    }

    private boolean active() {
        return this.tour != null && !this.projectName.isBlank();
    }

    private int pendingBlocks() {
        try {
            ProjectHomeViewState state = this.stateController.loadState(this.projectName, this.statusKey, false);
            PendingChangeSummary pending = state.pendingChanges();
            return pending.addedBlocks() + pending.removedBlocks() + pending.changedBlocks();
        } catch (RuntimeException exception) {
            return this.baselinePendingBlocks < 0 ? 0 : this.baselinePendingBlocks;
        }
    }

    private void clear() {
        this.projectName = "";
        this.variantId = "";
        this.statusKey = "luma.status.project_ready";
        this.onboardingService = new ClientOnboardingService();
        this.tour = null;
        this.baselinePendingBlocks = -1;
        this.refreshCooldown = 0;
    }
}
