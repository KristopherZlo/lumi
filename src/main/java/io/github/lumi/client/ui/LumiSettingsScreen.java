package io.github.lumi.client.ui;

import io.github.lumi.telemetry.TelemetryService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Client-local controls for the diagnostic data that Lumi may send. */
public final class LumiSettingsScreen extends Screen {
    private final Screen parent;
    private final TelemetryService telemetry;
    private int panelX;
    private int panelY;
    private int panelWidth;

    public LumiSettingsScreen(Screen parent, TelemetryService telemetry) {
        super(Component.translatable("luma.screen.settings.title", "Lumi"));
        this.parent = parent;
        this.telemetry = java.util.Objects.requireNonNull(telemetry, "telemetry");
    }

    @Override
    protected void init() {
        panelWidth = Math.min(430, width - 24);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(12, (height - 270) / 2);
        boolean enabled = telemetry.settings().enabled();
        addRenderableWidget(Button.builder(
                Component.translatable("luma.settings.telemetry_enabled")
                        .append(": ")
                        .append(Component.translatable(enabled ? "options.on" : "options.off")),
                ignored -> {
                    telemetry.setEnabled(!enabled);
                    rebuildWidgets();
                }).bounds(panelX + 16, panelY + 112, panelWidth - 32, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("luma.settings.telemetry_clear_queue"),
                ignored -> {
                    telemetry.clearLocalQueue();
                    rebuildWidgets();
                }).bounds(panelX + 16, panelY + 194, panelWidth - 32, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.close"), ignored -> onClose())
                .bounds(panelX + panelWidth - 76, panelY + 232, 60, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 270, 0xee15181d);
        graphics.drawString(font, title, panelX + 16, panelY + 18, 0xffffffff, false);
        graphics.drawString(font, Component.translatable("luma.settings.telemetry_title"),
                panelX + 16, panelY + 44, 0xffffd166, false);
        int y = panelY + 64;
        for (var line : font.split(
                Component.translatable("luma.settings.telemetry_enabled_help"),
                panelWidth - 32)) {
            graphics.drawString(font, line, panelX + 16, y, 0xffaeb6c2, false);
            y += 11;
        }
        graphics.drawString(font,
                Component.translatable("luma.settings.telemetry_pending",
                        telemetry.pendingEventCount()),
                panelX + 16, panelY + 148, 0xffd8dee7, false);
        graphics.drawString(font,
                Component.translatable("luma.settings.telemetry_last_send",
                        telemetry.lastSendSummary()),
                panelX + 16, panelY + 166, 0xffd8dee7, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
