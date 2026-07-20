package io.github.lumi.client.ui;

import io.github.lumi.client.onboarding.ClientContextualHelpHint;
import io.github.lumi.network.CleanupResultPayload;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Cleanup page that requires a correlated dry run before apply. */
public final class LumiCleanupScreen extends LumiModalScreen {
    private static final int PANEL_WIDTH = 460;
    private static final int BASE_PANEL_HEIGHT = 220;
    private final Screen parent;
    private final Supplier<UUID> inspect;
    private final Supplier<UUID> apply;
    private UUID pendingRequest;
    private CleanupResultPayload result;
    private String error = "";
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentOffset;
    private int resultScroll;
    private CleanupGeometry geometry;

    public LumiCleanupScreen(
            Screen parent, Supplier<UUID> inspect, Supplier<UUID> apply) {
        super(Component.translatable("luma.screen.cleanup.title"));
        this.parent = parent;
        this.inspect = Objects.requireNonNull(inspect, "inspect");
        this.apply = Objects.requireNonNull(apply, "apply");
    }

    @Override
    protected void init() {
        beginScreenInit();
        LumiModalLayout layout = fitPanel(width, height, 0);
        applyLayout(layout);
        boolean hintVisible = addContextualHint(
                ClientContextualHelpHint.CLEANUP,
                panelX + 16, panelY + 76, panelWidth - 32);
        contentOffset = hintVisible ? contextualHintOffset(8) : 0;
        layout = fitPanel(width, height, contentOffset);
        applyLayout(layout);
        geometry = cleanupGeometry(
                panelHeight, hintVisible ? contextualHintOffset(0) : 0);
        if (hintVisible) {
            moveContextualHint(
                    panelX + 16, panelY + geometry.hintY());
        }
        int buttonWidth = Math.max(0, (panelWidth - 40) / 3);
        int y = panelY + geometry.actionY();
        LumiButton inspectButton = addButton(
                panelX + 16, y, buttonWidth,
                Component.translatable("luma.action.inspect_unused_files"),
                () -> request(inspect), LumiButton.Kind.PRIMARY);
        inspectButton.active = pendingRequest == null;
        LumiButton clean = addButton(
                panelX + 20 + buttonWidth, y, buttonWidth,
                Component.translatable("luma.action.clean_up"),
                () -> request(apply), LumiButton.Kind.DANGER);
        clean.active = pendingRequest == null && result != null
                && result.succeeded() && !result.applied()
                && result.commits() + result.objects() > 0;
        addButton(panelX + 24 + buttonWidth * 2, y, buttonWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiButton.Kind.NORMAL);
    }

    private void applyLayout(LumiModalLayout layout) {
        panelX = layout.x();
        panelY = layout.y();
        panelWidth = layout.width();
        panelHeight = layout.height();
    }

    private void request(Supplier<UUID> sender) {
        try {
            pendingRequest = sender.get();
            error = "";
            resultScroll = 0;
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
        resultScroll = 0;
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
            renderWindow(graphics, panelX, panelY, panelWidth, panelHeight);
            graphics.drawString(font, title, panelX + 16,
                    panelY + (geometry.compact() ? 14 : 18),
                    LumiTheme.TEXT, false);
            if (!geometry.compact() || geometry.hintHeight() == 0) {
                drawWrapped(graphics,
                        Component.translatable("luma.cleanup.actions_help"),
                        panelY + (geometry.compact() ? 34 : 40),
                        LumiTheme.MUTED, panelWidth);
            }
            if (geometry.resultHeight() > 0) {
                renderPanel(graphics, panelX + 16,
                        panelY + geometry.resultY(),
                        panelWidth - 32, geometry.resultHeight());
                renderResult(graphics);
            }
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
    }

    private void renderResult(GuiGraphics graphics) {
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
        List<net.minecraft.util.FormattedCharSequence> lines =
                font.split(status, panelWidth - 64);
        int visible = visibleResultLines(geometry.resultHeight());
        resultScroll = Math.min(resultScroll, Math.max(0, lines.size() - visible));
        int y = panelY + geometry.resultY() + 6;
        for (var line : lines.stream().skip(resultScroll).limit(visible).toList()) {
            graphics.drawString(font, line, panelX + 32, y,
                    error.isEmpty() ? LumiTheme.TEXT : LumiTheme.DANGER,
                    false);
            y += 11;
        }
        renderScrollbar(
                graphics, panelX + 20,
                panelY + geometry.resultY() + 5,
                panelWidth - 38,
                Math.max(0, geometry.resultHeight() - 10),
                lines.size(), visible, resultScroll,
                value -> resultScroll = value);
    }

    private void drawWrapped(
            GuiGraphics graphics, Component text, int y, int color, int panelWidth) {
        for (var line : font.split(text, panelWidth - 64)) {
            graphics.drawString(font, line, panelX + 32, y, color, false);
            y += 11;
        }
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (geometry != null
                && x >= panelX + 16 && x < panelX + panelWidth - 16
                && y >= panelY + geometry.resultY()
                && y < panelY + geometry.resultY() + geometry.resultHeight()) {
            int replacement = Math.max(0, resultScroll
                    + (verticalAmount < 0 ? 1 : -1));
            if (replacement != resultScroll) {
                resultScroll = replacement;
            }
            return true;
        }
        return super.mouseScrolled(
                mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    static LumiModalLayout fitPanel(
            int screenWidth, int screenHeight, int contentOffset) {
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(1, screenWidth - 16));
        int panelHeight = Math.min(
                BASE_PANEL_HEIGHT + Math.max(0, contentOffset),
                Math.max(1, screenHeight - 16));
        return new LumiModalLayout(
                Math.max(0, (screenWidth - panelWidth) / 2),
                Math.max(0, (screenHeight - panelHeight) / 2),
                panelWidth, panelHeight);
    }

    static CleanupGeometry cleanupGeometry(int panelHeight, int hintHeight) {
        int actionY = Math.max(0, panelHeight - 28);
        boolean compact = panelHeight < BASE_PANEL_HEIGHT + Math.max(0, hintHeight);
        int preferredHintY = compact ? 34 : 76;
        int hintY = hintHeight == 0 ? preferredHintY : Math.max(
                24, Math.min(preferredHintY, actionY - hintHeight - 26));
        int resultY = hintHeight == 0
                ? (compact ? 58 : 78)
                : hintY + hintHeight + 5;
        int resultHeight = Math.max(0, actionY - 6 - resultY);
        return new CleanupGeometry(
                hintY, Math.max(0, hintHeight), resultY,
                resultHeight, actionY, compact);
    }

    static int visibleResultLines(int resultHeight) {
        return resultHeight < 15 ? 0 : 1 + (resultHeight - 15) / 11;
    }

    record CleanupGeometry(
            int hintY,
            int hintHeight,
            int resultY,
            int resultHeight,
            int actionY,
            boolean compact) {
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
