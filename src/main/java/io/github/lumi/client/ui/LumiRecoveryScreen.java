package io.github.lumi.client.ui;

import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Blocking choice for an interrupted durable apply journal. */
public final class LumiRecoveryScreen extends Screen {
    private static final int PANEL_WIDTH = 400;
    private static final int PANEL_HEIGHT = 142;
    private final Screen parent;
    private final Runnable resumeTarget;
    private final Runnable returnCheckpoint;
    private String error = "";
    private int panelX;
    private int panelY;

    public LumiRecoveryScreen(
            Screen parent,
            Runnable resumeTarget,
            Runnable returnCheckpoint) {
        super(Component.translatable("luma.recovery.found_title"));
        this.parent = parent;
        this.resumeTarget = Objects.requireNonNull(resumeTarget, "resumeTarget");
        this.returnCheckpoint = Objects.requireNonNull(returnCheckpoint, "returnCheckpoint");
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        int contentX = panelX + 20;
        int buttonWidth = (panelWidth - 48) / 2;
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.recovery_restore"),
                ignored -> submit(resumeTarget))
                .bounds(contentX, panelY + 96, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.return_before_restore"),
                ignored -> submit(returnCheckpoint))
                .bounds(contentX + buttonWidth + 8, panelY + 96, buttonWidth, 20).build());
    }

    private void submit(Runnable intent) {
        try {
            intent.run();
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi recovery could not start" : failed.getMessage();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        graphics.fill(panelX, panelY, panelX + panelWidth,
                panelY + PANEL_HEIGHT, 0xee15181d);
        graphics.drawCenteredString(font, title, width / 2, panelY + 18, 0xffffffff);
        graphics.drawCenteredString(font,
                Component.translatable("luma.recovery.found_help"),
                width / 2, panelY + 42, 0xffaeb6c2);
        graphics.drawCenteredString(font,
                Component.translatable("luma.recovery.actions_help"),
                width / 2, panelY + 58, 0xffaeb6c2);
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(error),
                    width / 2, panelY + 76, 0xffff6b6b);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean isPauseScreen() { return false; }
}
