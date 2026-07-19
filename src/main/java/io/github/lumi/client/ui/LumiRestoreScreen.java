package io.github.lumi.client.ui;

import io.github.lumi.domain.model.CommitId;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minimal full-commit Restore confirmation. */
public final class LumiRestoreScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 380;
    private static final int PANEL_HEIGHT = 120;
    private final Screen parent;
    private final CommitId target;
    private final Consumer<CommitId> restore;
    private int panelX;
    private int panelY;
    private String error = "";

    public LumiRestoreScreen(Screen parent, CommitId target, String message,
            Consumer<CommitId> restore) {
        super(Component.translatable("luma.restore.confirm_title", message));
        this.parent = parent;
        this.target = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        this.restore = Objects.requireNonNull(restore, "restore");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(8, (height - PANEL_HEIGHT) / 2);
        int buttonWidth = Math.max(80, (panelWidth - 48) / 2);
        addLegacyButton(
                panelX + 16, panelY + PANEL_HEIGHT - 34, buttonWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
        addLegacyButton(
                panelX + panelWidth - 16 - buttonWidth,
                panelY + PANEL_HEIGHT - 34, buttonWidth,
                Component.translatable("luma.action.restore"),
                this::submit, LumiLegacyButton.Kind.PRIMARY);
    }

    private void submit() {
        try {
            restore.accept(target);
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null ? "Lumi Restore could not start"
                    : failed.getMessage();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            int panelWidth = Math.min(PANEL_WIDTH, width - 32);
            renderLegacyWindow(graphics, panelX, panelY, panelWidth, PANEL_HEIGHT);
            graphics.drawCenteredString(
                    font, clippedCenteredHeader(
                            title, width / 2,
                            panelX + 16, panelX + panelWidth - 16),
                    width / 2, panelY + 24, LegacyLumiTheme.TEXT);
            if (!error.isEmpty()) {
                graphics.drawCenteredString(
                        font,
                        font.plainSubstrByWidth(error, Math.max(1, panelWidth - 32)),
                        width / 2, panelY + 48, LegacyLumiTheme.DANGER);
            }
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
