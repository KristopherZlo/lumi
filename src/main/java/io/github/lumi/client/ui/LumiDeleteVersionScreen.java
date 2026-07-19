package io.github.lumi.client.ui;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit confirmation for a durable soft-delete marker. */
public final class LumiDeleteVersionScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 180;
    private final Screen parent;
    private final HistorySnapshotPayload.Version version;
    private final Consumer<CommitId> delete;
    private int panelX;
    private int panelY;
    private String error = "";

    public LumiDeleteVersionScreen(
            Screen parent,
            HistorySnapshotPayload.Version version,
            Consumer<CommitId> delete) {
        super(Component.translatable("luma.save_details.delete_title"));
        this.parent = parent;
        this.version = Objects.requireNonNull(version, "version");
        this.delete = Objects.requireNonNull(delete, "delete");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        int buttonWidth = (panelWidth - 48) / 2;
        addLegacyButton(panelX + 20, panelY + 138, buttonWidth,
                Component.translatable("luma.action.delete_save"),
                this::delete, LumiLegacyButton.Kind.DANGER);
        addLegacyButton(panelX + 28 + buttonWidth, panelY + 138, buttonWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    private void delete() {
        try {
            delete.accept(version.id());
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("luma.status.version_deleted"), true);
            }
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi save could not be deleted" : failed.getMessage();
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
                    title, centerX, contentLeft, contentRight),
                    centerX, panelY + 18, LegacyLumiTheme.TEXT);
            graphics.drawCenteredString(font, clippedCenteredHeader(
                    Component.literal(version.message()),
                    centerX, contentLeft, contentRight),
                    centerX, panelY + 42, LegacyLumiTheme.TEXT);
            graphics.drawCenteredString(font, clippedCenteredHeader(
                    Component.translatable("luma.save_details.delete_help"),
                    centerX, contentLeft, contentRight),
                    centerX, panelY + 68, LegacyLumiTheme.MUTED);
            graphics.drawCenteredString(font, clippedCenteredHeader(
                    Component.translatable("luma.save_details.delete_warning"),
                    centerX, contentLeft, contentRight),
                    centerX, panelY + 90, LegacyLumiTheme.ACCENT);
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
    @Override public void onClose() { minecraft.setScreen(parent); }
}
