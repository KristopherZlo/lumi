package io.github.lumi.client.ui;

import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit confirmation before replacing the latest save. */
public final class LumiAmendConfirmationScreen extends LumiModalScreen {
    private static final int PANEL_HEIGHT = 150;

    private final Screen parent;
    private final Runnable amend;
    private LumiModalLayout layout;

    public LumiAmendConfirmationScreen(Screen parent, Runnable amend) {
        super(parent, Component.translatable("luma.amend.confirm_title"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.amend = Objects.requireNonNull(amend, "amend");
    }

    @Override
    protected void init() {
        beginScreenInit();
        layout = LumiModalLayout.fit(width, height, PANEL_HEIGHT);
        int buttonWidth = Math.max(1, (layout.width() - 18) / 2);
        int actionY = layout.y() + layout.height() - 28;
        addButton(layout.x() + 6, actionY, buttonWidth,
                Component.translatable("gui.yes"), this::confirm,
                LumiButton.Kind.DANGER);
        addButton(layout.x() + 12 + buttonWidth, actionY, buttonWidth,
                Component.translatable("gui.no"), this::onClose,
                LumiButton.Kind.NORMAL);
    }

    private void confirm() {
        minecraft.setScreen(parent);
        amend.run();
    }

    @Override
    public void render(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(
                graphics, mouseX, mouseY);
        try {
            renderWindow(graphics, layout.x(), layout.y(),
                    layout.width(), layout.height());
            graphics.drawCenteredString(font, title, width / 2,
                    layout.y() + 18, LumiTheme.TEXT);
            int y = layout.y() + 48;
            for (var line : font.split(
                    Component.translatable("luma.amend.confirm_help"),
                    layout.width() - 32)) {
                graphics.drawCenteredString(
                        font, line, width / 2, y, LumiTheme.MUTED);
                y += 11;
            }
            super.render(
                    graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
