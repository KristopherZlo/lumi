package io.github.lumi.client.ui;

import io.github.lumi.domain.model.CommitId;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Full Restore confirmation with the retained durable-entity exclusion choice. */
public final class LumiRestoreScreen extends Screen {
    private static final int PANEL_WIDTH = 520;
    private static final int PANEL_HEIGHT = 166;
    private final Screen parent;
    private final CommitId target;
    private final BiConsumer<CommitId, Boolean> restore;
    private String error = "";
    private int panelX;
    private int panelY;

    public LumiRestoreScreen(
            Screen parent,
            CommitId target,
            String message,
            BiConsumer<CommitId, Boolean> restore) {
        super(Component.translatable("luma.restore.confirm_title", message));
        this.parent = parent;
        this.target = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        this.restore = Objects.requireNonNull(restore, "restore");
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        int contentX = panelX + 20;
        int buttonWidth = (panelWidth - 56) / 3;
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.restore_whole_save"),
                ignored -> submit(true))
                .bounds(contentX, panelY + 124, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.restore_without_entities"),
                ignored -> submit(false))
                .bounds(contentX + buttonWidth + 8, panelY + 124, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.cancel"), ignored -> onClose())
                .bounds(contentX + (buttonWidth + 8) * 2, panelY + 124, buttonWidth, 20).build());
    }

    private void submit(boolean includeEntities) {
        try {
            restore.accept(target, includeEntities);
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi Restore could not start" : failed.getMessage();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        graphics.fill(panelX, panelY, panelX + panelWidth,
                panelY + PANEL_HEIGHT, 0xee15181d);
        graphics.drawCenteredString(font, title, width / 2, panelY + 17, 0xffffffff);
        graphics.drawCenteredString(font,
                Component.translatable("luma.restore.confirm_help"),
                width / 2, panelY + 42, 0xffaeb6c2);
        graphics.drawCenteredString(font,
                Component.translatable("luma.restore.confirm_safety"),
                width / 2, panelY + 58, 0xffaeb6c2);
        graphics.drawCenteredString(font,
                Component.translatable("luma.restore.entities_help"),
                width / 2, panelY + 78, 0xff8f9aa8);
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(error),
                    width / 2, panelY + 102, 0xffff6b6b);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
