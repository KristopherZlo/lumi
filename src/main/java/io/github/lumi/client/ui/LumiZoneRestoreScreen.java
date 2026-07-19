package io.github.lumi.client.ui;

import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit confirmation for a zone-scoped verified Restore. */
public final class LumiZoneRestoreScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 180;
    private final Screen cancelParent;
    private final Screen successParent;
    private final HistorySnapshotPayload.ZoneView zone;
    private final HistorySnapshotPayload.Version version;
    private final BiConsumer<UUID, io.github.lumi.domain.model.CommitId> restore;
    private int panelX;
    private int panelY;
    private String error = "";

    public LumiZoneRestoreScreen(
            Screen cancelParent,
            Screen successParent,
            HistorySnapshotPayload.ZoneView zone,
            HistorySnapshotPayload.Version version,
            BiConsumer<UUID, io.github.lumi.domain.model.CommitId> restore) {
        super(Component.translatable("luma.action.restore"));
        this.cancelParent = cancelParent;
        this.successParent = successParent;
        this.zone = Objects.requireNonNull(zone, "zone");
        this.version = Objects.requireNonNull(version, "version");
        this.restore = Objects.requireNonNull(restore, "restore");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        int buttonWidth = (panelWidth - 48) / 2;
        addLegacyButton(panelX + 20, panelY + 138, buttonWidth,
                Component.translatable("luma.action.restore"),
                this::restore, LumiLegacyButton.Kind.PRIMARY);
        addLegacyButton(panelX + 28 + buttonWidth, panelY + 138, buttonWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    private void restore() {
        try {
            restore.accept(zone.id(), version.id());
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("luma.status.zone_restore_started"), true);
            }
            minecraft.setScreen(successParent);
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi zone Restore could not start" : failed.getMessage();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            int panelWidth = Math.min(PANEL_WIDTH, width - 32);
            int centerX = width / 2;
            int contentLeft = panelX + 20;
            int contentRight = panelX + panelWidth - 20;
            renderLegacyWindow(graphics, panelX, panelY, panelWidth, PANEL_HEIGHT);
            graphics.drawCenteredString(font, clippedCenteredHeader(
                    Component.translatable(
                            "luma.restore.confirm_title", version.message()),
                    centerX, contentLeft, contentRight),
                    centerX, panelY + 18, LegacyLumiTheme.TEXT);
            graphics.drawCenteredString(font, clippedCenteredHeader(
                    Component.translatable("luma.restore.confirm_zone_help"),
                    centerX, contentLeft, contentRight),
                    centerX, panelY + 48, LegacyLumiTheme.MUTED);
            graphics.drawCenteredString(font, clippedCenteredHeader(
                    Component.translatable(
                            "luma.zones.details_title", zone.name()),
                    centerX, contentLeft, contentRight),
                    centerX, panelY + 68, zone.color());
            graphics.drawCenteredString(font, clippedCenteredHeader(
                    Component.translatable("luma.restore.confirm_safety"),
                    centerX, contentLeft, contentRight),
                    centerX, panelY + 92, LegacyLumiTheme.ACCENT);
            if (!error.isEmpty()) {
                graphics.drawCenteredString(font, clippedCenteredHeader(
                        errorText(error), centerX, contentLeft, contentRight),
                        centerX, panelY + 116, LegacyLumiTheme.DANGER);
            }
            super.render(
                    graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(cancelParent); }
}
