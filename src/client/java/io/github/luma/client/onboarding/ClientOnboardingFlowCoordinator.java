package io.github.luma.client.onboarding;

import io.github.luma.LumaMod;
import io.github.luma.client.input.KeyBindingState;
import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.onboarding.OnboardingTour;
import io.github.luma.ui.overlay.RoundedHudRenderer;
import io.github.luma.ui.screen.OnboardingScreen;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.github.luma.ui.state.OnboardingHoldGate;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.FormattedCharSequence;

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
    private static final int PANEL_PADDING = 8;
    private static final int MAX_HELP_LINES = 3;
    private static final int REQUIRED_WORLD_EDITS = 5;
    private static final ClientOnboardingFlowCoordinator INSTANCE = new ClientOnboardingFlowCoordinator();

    private final ProjectHomeScreenController stateController = new ProjectHomeScreenController();
    private final KeyBindingState keyBindingState = new KeyBindingState();
    private final OnboardingHoldGate holdGate = new OnboardingHoldGate();
    private String projectName = "";
    private String variantId = "";
    private String statusKey = "luma.status.project_ready";
    private ClientOnboardingService onboardingService;
    private OnboardingTour tour;
    private int baselinePendingBlocks = -1;
    private int worldEditCount;
    private int refreshCooldown;
    private long lastHoldSampleMillis;

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
        this.worldEditCount = 0;
        this.refreshCooldown = 0;
        this.resetHoldGate();
    }

    public boolean suppressesLumiShortcuts() {
        return false;
    }

    public boolean trackingWorldStep() {
        return this.active() && ("break_block".equals(this.tour.currentPageId())
                || "preview_changes".equals(this.tour.currentPageId()));
    }

    public void tick(Minecraft client) {
        if (!this.active() || client == null) {
            return;
        }
        if (client.screen != null) {
            return;
        }
        if ("preview_changes".equals(this.tour.currentPageId())) {
            this.tickPendingPreview(client);
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
            this.worldEditCount = 0;
            return;
        }
        this.worldEditCount = trackedWorldEdits(this.baselinePendingBlocks, pendingBlocks);
        if (this.worldEditCount < REQUIRED_WORLD_EDITS) {
            return;
        }

        this.tour.advanceAfterWorldEdit();
        this.returnToOnboarding(client);
    }

    private void tickPendingPreview(Minecraft client) {
        KeyMapping actionKey = LumiClientKeyBindings.key(LumiClientKeyBindings.Role.ACTION);
        boolean held = this.keyBindingState.isDown(client, actionKey);
        long now = Util.getMillis();
        long elapsedMillis = held && this.lastHoldSampleMillis > 0L
                ? Math.min(100L, now - this.lastHoldSampleMillis)
                : 0L;
        this.lastHoldSampleMillis = held ? now : 0L;
        if (!this.holdGate.update(held, elapsedMillis)) {
            return;
        }

        this.tour.advanceAfterPendingPreview();
        this.returnToOnboarding(client);
    }

    private void render(GuiGraphics drawContext, net.minecraft.client.DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        if (!this.active()
                || client.options.hideGui
                || client.screen != null) {
            return;
        }
        if (!"break_block".equals(this.tour.currentPageId())
                && !"preview_changes".equals(this.tour.currentPageId())) {
            return;
        }

        Font font = client.font;
        boolean previewStep = "preview_changes".equals(this.tour.currentPageId());
        List<FormattedCharSequence> helpLines = this.helpLines(font, previewStep);
        int height = this.promptHeight(previewStep, helpLines.size());
        int x = Math.max(8, (drawContext.guiWidth() - PANEL_WIDTH) / 2);
        int y = Math.max(8, drawContext.guiHeight() - height - 30);

        RoundedHudRenderer.card(drawContext, x, y, PANEL_WIDTH, height);
        drawContext.drawString(font, this.tour.headerText(), x + PANEL_PADDING, y + PANEL_PADDING, 0xFF98A6B3, false);
        drawContext.drawString(font, this.tour.pageName(), x + PANEL_PADDING, y + 20, 0xFF4ADE80, false);

        int cursorY = y + 34;
        if (previewStep) {
            cursorY = this.drawPreviewShortcut(drawContext, font, x + PANEL_PADDING, cursorY);
        }
        for (FormattedCharSequence line : helpLines) {
            drawContext.drawString(font, line, x + PANEL_PADDING, cursorY, 0xFFF3F7FA, false);
            cursorY += 10;
        }
        if ("break_block".equals(this.tour.currentPageId())) {
            drawContext.drawString(
                    font,
                    Component.translatable(
                            "luma.onboarding.world_edit_counter",
                            Math.min(REQUIRED_WORLD_EDITS, this.worldEditCount),
                            REQUIRED_WORLD_EDITS
                    ),
                    x + PANEL_PADDING,
                    cursorY + 2,
                    0xFF98A6B3,
                    false
            );
        }
    }

    private List<FormattedCharSequence> helpLines(Font font, boolean previewStep) {
        Component text = previewStep
                ? Component.translatable("luma.onboarding.preview_changes_suffix")
                : this.tour.helpText();
        return font.split(text, PANEL_WIDTH - (PANEL_PADDING * 2))
                .stream()
                .limit(MAX_HELP_LINES)
                .toList();
    }

    private int promptHeight(boolean previewStep, int helpLineCount) {
        int shortcutHeight = previewStep ? 25 : 0;
        int counterHeight = previewStep ? 0 : 14;
        return PANEL_PADDING + 26 + shortcutHeight + (helpLineCount * 10) + counterHeight + PANEL_PADDING;
    }

    private int drawPreviewShortcut(GuiGraphics drawContext, Font font, int x, int y) {
        Component hold = Component.translatable("luma.onboarding.preview_changes_hold");
        drawContext.drawString(font, hold, x, y + 6, 0xFFF3F7FA, false);
        int cursorX = x + font.width(hold) + 5;
        KeyMapping actionKey = LumiClientKeyBindings.key(LumiClientKeyBindings.Role.ACTION);
        RoundedHudRenderer.key(
                drawContext,
                actionKey,
                cursorX,
                y,
                "Action",
                false,
                this.keyBindingState.isDown(Minecraft.getInstance(), actionKey)
        );
        return y + 25;
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

    static int trackedWorldEdits(int baselinePendingBlocks, int pendingBlocks) {
        if (baselinePendingBlocks < 0) {
            return 0;
        }
        return Math.max(0, pendingBlocks - baselinePendingBlocks);
    }

    private void returnToOnboarding(Minecraft client) {
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

    private void clear() {
        this.projectName = "";
        this.variantId = "";
        this.statusKey = "luma.status.project_ready";
        this.onboardingService = null;
        this.tour = null;
        this.baselinePendingBlocks = -1;
        this.worldEditCount = 0;
        this.refreshCooldown = 0;
        this.resetHoldGate();
    }

    private void resetHoldGate() {
        this.holdGate.reset();
        this.lastHoldSampleMillis = 0L;
    }
}
