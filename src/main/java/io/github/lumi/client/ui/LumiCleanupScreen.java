package io.github.lumi.client.ui;

import io.github.lumi.client.onboarding.ClientContextualHelpHint;
import io.github.lumi.network.CleanupResultPayload;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Legacy cleanup page that requires a correlated dry run before apply. */
public final class LumiCleanupScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 460;
    private static final int BASE_PANEL_HEIGHT = 220;
    private static final int HINT_PANEL_HEIGHT = 276;
    private final Screen parent;
    private final Supplier<UUID> inspect;
    private final Supplier<UUID> apply;
    private UUID pendingRequest;
    private CleanupResultPayload result;
    private String error = "";
    private int panelX;
    private int panelY;
    private int panelHeight;
    private int contentOffset;

    public LumiCleanupScreen(
            Screen parent, Supplier<UUID> inspect, Supplier<UUID> apply) {
        super(Component.translatable("luma.screen.cleanup.title"));
        this.parent = parent;
        this.inspect = Objects.requireNonNull(inspect, "inspect");
        this.apply = Objects.requireNonNull(apply, "apply");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelHeight = HINT_PANEL_HEIGHT;
        panelY = Math.max(8, (height - panelHeight) / 2);
        boolean hintVisible = addContextualHint(
                ClientContextualHelpHint.CLEANUP,
                panelX + 16, panelY + 76, panelWidth - 32);
        contentOffset = hintVisible ? 56 : 0;
        if (!hintVisible) {
            panelHeight = BASE_PANEL_HEIGHT;
            panelY = Math.max(8, (height - panelHeight) / 2);
        }
        int buttonWidth = (panelWidth - 48) / 3;
        int y = panelY + panelHeight - 30;
        LumiLegacyButton inspectButton = addLegacyButton(
                panelX + 16, y, buttonWidth,
                Component.translatable("luma.action.inspect_unused_files"),
                () -> request(inspect), LumiLegacyButton.Kind.PRIMARY);
        inspectButton.active = pendingRequest == null;
        LumiLegacyButton clean = addLegacyButton(
                panelX + 20 + buttonWidth, y, buttonWidth,
                Component.translatable("luma.action.clean_up"),
                () -> request(apply), LumiLegacyButton.Kind.DANGER);
        clean.active = pendingRequest == null && result != null
                && result.succeeded() && !result.applied()
                && result.commits() + result.objects() > 0;
        addLegacyButton(panelX + 24 + buttonWidth * 2, y, buttonWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    private void request(Supplier<UUID> sender) {
        try {
            pendingRequest = sender.get();
            error = "";
            rebuildWidgets();
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi cleanup could not start" : failed.getMessage();
        }
    }

    public void accept(CleanupResultPayload response) {
        Objects.requireNonNull(response, "response");
        if (!response.requestId().equals(pendingRequest)) {
            return;
        }
        pendingRequest = null;
        result = response;
        error = response.error();
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            int panelWidth = Math.min(PANEL_WIDTH, width - 32);
            renderLegacyWindow(graphics, panelX, panelY, panelWidth, panelHeight);
            graphics.drawString(font, title, panelX + 16, panelY + 18,
                    LegacyLumiTheme.TEXT, false);
            drawWrapped(graphics,
                    Component.translatable("luma.cleanup.actions_help"),
                    panelY + 40, LegacyLumiTheme.MUTED, panelWidth);
            renderLegacyPanel(graphics, panelX + 16,
                    panelY + 78 + contentOffset,
                    panelWidth - 32, 86);
            renderResult(graphics, panelWidth);
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void renderResult(GuiGraphics graphics, int panelWidth) {
        Component status;
        if (!error.isEmpty()) {
            status = errorText(error);
        } else if (pendingRequest != null) {
            status = Component.translatable("luma.action.checking_updates");
        } else if (result == null) {
            status = Component.translatable("luma.status.cleanup_ready");
        } else if (result.commits() + result.objects() == 0) {
            status = Component.translatable("luma.cleanup.empty");
        } else {
            status = Component.translatable(
                    result.applied()
                            ? "luma.cleanup.applied_counts"
                            : "luma.cleanup.results_counts",
                    result.commits(), result.objects());
        }
        drawWrapped(graphics, status, panelY + 94 + contentOffset,
                error.isEmpty() ? LegacyLumiTheme.TEXT : LegacyLumiTheme.DANGER,
                panelWidth);
    }

    private void drawWrapped(
            GuiGraphics graphics, Component text, int y, int color, int panelWidth) {
        for (var line : font.split(text, panelWidth - 64)) {
            graphics.drawString(font, line, panelX + 32, y, color, false);
            y += 11;
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
