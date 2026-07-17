package io.github.lumi.client.ui;

import io.github.lumi.telemetry.TelemetryService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Client-local controls for the diagnostic data that Lumi may send. */
public final class LumiSettingsScreen extends LumiLegacyPageScreen {
    private final TelemetryService telemetry;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    public LumiSettingsScreen(Screen parent, TelemetryService telemetry) {
        super(parent, Component.translatable("luma.screen.settings.title", "Lumi"),
                LegacyProjectTab.SETTINGS);
        this.telemetry = java.util.Objects.requireNonNull(telemetry, "telemetry");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        LegacyWorkspaceLayout page = pageLayout();
        panelX = page.contentX();
        panelY = page.windowY();
        panelWidth = page.contentWidth();
        panelHeight = page.windowHeight();
        boolean enabled = telemetry.settings().enabled();
        addLegacyButton(panelX + 16, panelY + 112, panelWidth - 32,
                Component.translatable("luma.settings.telemetry_enabled")
                        .append(": ")
                        .append(Component.translatable(enabled ? "options.on" : "options.off")),
                () -> {
                    telemetry.setEnabled(!enabled);
                    rebuildWidgets();
                }, enabled
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        addLegacyButton(panelX + 16, panelY + 194, panelWidth - 32,
                Component.translatable("luma.settings.telemetry_clear_queue"),
                () -> {
                    telemetry.clearLocalQueue();
                    rebuildWidgets();
                }, LumiLegacyButton.Kind.DANGER);
        addLegacyButton(panelX + panelWidth - 76, panelY + 232, 60,
                Component.translatable("luma.action.close"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        renderLegacyPage(graphics, panelX, panelY, panelWidth, panelHeight);
        renderLegacyPanel(graphics, panelX + 12, panelY + 38,
                panelWidth - 24, 176);
        graphics.drawString(font, title, panelX + 16, panelY + 18,
                LegacyLumiTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("luma.settings.telemetry_title"),
                panelX + 20, panelY + 48, LegacyLumiTheme.ACCENT, false);
        int y = panelY + 64;
        for (var line : font.split(
                Component.translatable("luma.settings.telemetry_enabled_help"),
                panelWidth - 40)) {
            graphics.drawString(font, line, panelX + 20, y,
                    LegacyLumiTheme.MUTED, false);
            y += 11;
        }
        graphics.drawString(font,
                Component.translatable("luma.settings.telemetry_pending",
                        telemetry.pendingEventCount()),
                panelX + 20, panelY + 148, LegacyLumiTheme.TEXT, false);
        graphics.drawString(font,
                Component.translatable("luma.settings.telemetry_last_send",
                        telemetry.lastSendSummary()),
                panelX + 20, panelY + 166, LegacyLumiTheme.TEXT, false);
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
}
