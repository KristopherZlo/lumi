package io.github.lumi.client.ui;

import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Blocking choice for an interrupted durable apply journal. */
public final class LumiRecoveryScreen extends LumiLegacyModalScreen {
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
        addLegacyButton(contentX, panelY + 96, buttonWidth,
                Component.translatable("luma.action.recovery_restore"),
                () -> submit(resumeTarget), LumiLegacyButton.Kind.PRIMARY);
        addLegacyButton(contentX + buttonWidth + 8, panelY + 96, buttonWidth,
                Component.translatable("luma.action.return_before_restore"),
                () -> submit(returnCheckpoint), LumiLegacyButton.Kind.DANGER);
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
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        renderLegacyWindow(graphics, panelX, panelY, panelWidth, PANEL_HEIGHT);
        graphics.drawCenteredString(font, title, width / 2, panelY + 18,
                LegacyLumiTheme.TEXT);
        graphics.drawCenteredString(font,
                Component.translatable("luma.recovery.found_help"),
                width / 2, panelY + 42, LegacyLumiTheme.MUTED);
        graphics.drawCenteredString(font,
                Component.translatable("luma.recovery.actions_help"),
                width / 2, panelY + 58, LegacyLumiTheme.MUTED);
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, errorText(error),
                    width / 2, panelY + 76, LegacyLumiTheme.DANGER);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean isPauseScreen() { return false; }
}
