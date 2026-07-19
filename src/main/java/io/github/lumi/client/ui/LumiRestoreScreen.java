package io.github.lumi.client.ui;

import io.github.lumi.domain.model.CommitId;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Full-commit Restore controls; area Restore has its own sword-driven screen. */
public final class LumiRestoreScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 540;
    private static final int PANEL_HEIGHT = 145;
    private final Screen parent;
    private final CommitId target;
    private final BiConsumer<CommitId, Boolean> fullRestore;
    private int panelX;
    private int panelY;
    private String error = "";

    public LumiRestoreScreen(Screen parent, CommitId target, String message,
            BiConsumer<CommitId, Boolean> fullRestore) {
        super(Component.translatable("luma.restore.confirm_title", message));
        this.parent = parent;
        this.target = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        this.fullRestore = Objects.requireNonNull(fullRestore, "fullRestore");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(8, (height - PANEL_HEIGHT) / 2);
        int innerWidth = panelWidth - 32;
        int buttonWidth = Math.max(80, (innerWidth - 8) / 2);
        addLegacyButton(panelX + 16, panelY + 82, buttonWidth,
                Component.translatable("luma.action.restore_whole_save"),
                () -> submit(true), LumiLegacyButton.Kind.PRIMARY);
        addLegacyButton(panelX + 24 + buttonWidth, panelY + 82, buttonWidth,
                Component.translatable("luma.action.restore_without_entities"),
                () -> submit(false), LumiLegacyButton.Kind.NORMAL);
        addLegacyButton(width / 2 - 60, panelY + PANEL_HEIGHT - 28, 120,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    private void submit(boolean includeEntities) {
        try {
            fullRestore.accept(target, includeEntities);
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
            graphics.drawCenteredString(font, title, width / 2, panelY + 14,
                    LegacyLumiTheme.TEXT);
            graphics.drawCenteredString(font,
                    Component.translatable("luma.restore.confirm_help"),
                    width / 2, panelY + 32, LegacyLumiTheme.MUTED);
            graphics.drawCenteredString(font,
                    Component.translatable("luma.restore.confirm_safety"),
                    width / 2, panelY + 44, LegacyLumiTheme.MUTED);
            graphics.drawCenteredString(font,
                    Component.translatable("luma.restore.entities_help"),
                    width / 2, panelY + 56, LegacyLumiTheme.MUTED);
            if (!error.isEmpty()) {
                graphics.drawCenteredString(font,
                        font.plainSubstrByWidth(error, Math.max(1, panelWidth - 32)),
                        width / 2, panelY + 70, LegacyLumiTheme.DANGER);
            }
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
