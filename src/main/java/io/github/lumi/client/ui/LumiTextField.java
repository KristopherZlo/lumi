package io.github.lumi.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** One-line Lumi input whose frame and text share one geometry. */
final class LumiTextField extends EditBox {
    static final int FRAME_HEIGHT = 18;
    static final int HORIZONTAL_PADDING = 6;
    static final int VERTICAL_PADDING = 4;
    private static final int CONTENT_HEIGHT = 14;

    private final int frameX;
    private final int frameY;
    private final int frameWidth;

    LumiTextField(
            Font font, int x, int y, int width, Component label) {
        super(font,
                x + HORIZONTAL_PADDING,
                y + VERTICAL_PADDING,
                Math.max(1, width - HORIZONTAL_PADDING * 2),
                CONTENT_HEIGHT,
                label);
        frameX = x;
        frameY = y;
        frameWidth = Math.max(1, width);
        setBordered(false);
        setTextColor(LumiTheme.TEXT);
    }

    @Override
    public void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LumiTheme.outlined(
                graphics, frameX, frameY, frameWidth, FRAME_HEIGHT,
                LumiTheme.INSET, LumiTheme.INSET_BORDER);
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible
                && mouseX >= frameX && mouseX < frameX + frameWidth
                && mouseY >= frameY && mouseY < frameY + FRAME_HEIGHT;
    }

    int frameX() { return frameX; }
    int frameY() { return frameY; }
    int frameWidth() { return frameWidth; }
}
