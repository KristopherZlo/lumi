package io.github.luma.ui.framework.component;

import io.github.luma.ui.framework.core.Sizing;
import io.github.luma.ui.framework.core.UIComponent;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class ButtonComponent extends UIComponent {

    private final Component text;
    private final Consumer<ButtonComponent> onPress;
    private boolean active = true;
    private boolean textShadow = true;
    private Renderer renderer = Renderer.flat(0xFF2A292C, 0xFF39363A, 0xFF18191B);

    ButtonComponent(Component text, Consumer<ButtonComponent> onPress) {
        this.text = text;
        this.onPress = onPress;
        this.sizing(Sizing.content(6), Sizing.fixed(18));
    }

    public ButtonComponent active(boolean active) {
        this.active = active;
        return this;
    }

    public ButtonComponent renderer(Renderer renderer) {
        this.renderer = renderer;
        return this;
    }

    public ButtonComponent textShadow(boolean textShadow) {
        this.textShadow = textShadow;
        return this;
    }

    @Override
    public int measureWidth(int availableWidth) {
        int padding = this.horizontalSizing().contentPadding();
        return Math.min(Math.max(18, availableWidth), Minecraft.getInstance().font.width(this.text) + padding * 2 + 8);
    }

    @Override
    public int measureHeight(int availableWidth, int availableHeight) {
        return 18;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int fill = !this.active
                ? this.renderer.disabled
                : this.isMouseOver(mouseX, mouseY) ? this.renderer.hover : this.renderer.fill;
        graphics.fill(this.x(), this.y(), this.x() + this.width(), this.y() + this.height(), fill);
        graphics.renderOutline(this.x(), this.y(), this.width(), this.height(), this.active ? 0xFF4B474C : 0xFF2A2B2E);

        Font font = Minecraft.getInstance().font;
        int textWidth = font.width(this.text);
        int textX = this.x() + Math.max(2, (this.width() - textWidth) / 2);
        int textY = this.y() + Math.max(1, (this.height() - font.lineHeight) / 2);
        graphics.drawString(font, this.text, textX, textY, this.active ? 0xFFF4F1EA : 0xFF777777, this.textShadow);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!this.active || event.button() != 0 || !this.isMouseOver(event.x(), event.y())) {
            return false;
        }
        this.onPress.accept(this);
        return true;
    }

    public static final class Renderer {
        private final int fill;
        private final int hover;
        private final int disabled;

        private Renderer(int fill, int hover, int disabled) {
            this.fill = fill;
            this.hover = hover;
            this.disabled = disabled;
        }

        public static Renderer flat(int fill, int hover, int disabled) {
            return new Renderer(fill, hover, disabled);
        }
    }
}
