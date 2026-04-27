package io.github.luma.ui.framework.component;

import io.github.luma.ui.framework.core.Color;
import io.github.luma.ui.framework.core.UIComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class LabelComponent extends UIComponent {

    private final Component text;
    private Color color = Color.ofRgb(0xFFFFFF);
    private boolean shadow = true;
    private int maxWidth = 260;

    LabelComponent(Component text) {
        this.text = text;
    }

    public LabelComponent color(Color color) {
        this.color = color;
        return this;
    }

    public LabelComponent shadow(boolean shadow) {
        this.shadow = shadow;
        return this;
    }

    public LabelComponent maxWidth(int maxWidth) {
        this.maxWidth = Math.max(1, maxWidth);
        return this;
    }

    @Override
    public int measureWidth(int availableWidth) {
        Font font = Minecraft.getInstance().font;
        int width = Math.min(this.maxWidth, Math.max(1, font.width(this.text)));
        return Math.min(Math.max(1, availableWidth), width);
    }

    @Override
    public int measureHeight(int availableWidth, int availableHeight) {
        Font font = Minecraft.getInstance().font;
        return Math.max(font.lineHeight, font.wordWrapHeight(this.text, this.wrapWidth(availableWidth)));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        graphics.drawWordWrap(font, this.text, this.x(), this.y(), this.wrapWidth(this.width()), this.color.argb(), this.shadow);
    }

    private int wrapWidth(int availableWidth) {
        return Math.max(1, Math.min(this.maxWidth, Math.max(1, availableWidth)));
    }
}
