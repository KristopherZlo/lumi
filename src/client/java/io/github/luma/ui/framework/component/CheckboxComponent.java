package io.github.luma.ui.framework.component;

import io.github.luma.ui.framework.core.Sizing;
import io.github.luma.ui.framework.core.UIComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class CheckboxComponent extends UIComponent {

    private final Component label;
    private final List<Consumer<Boolean>> subscribers = new ArrayList<>();
    private boolean checked;

    CheckboxComponent(Component label) {
        this.label = label;
        this.sizing(Sizing.content(), Sizing.fixed(16));
    }

    public CheckboxComponent checked(boolean checked) {
        this.checked = checked;
        return this;
    }

    public CheckboxComponent onChanged(Consumer<Boolean> subscriber) {
        this.subscribers.add(subscriber);
        return this;
    }

    @Override
    public int measureWidth(int availableWidth) {
        int labelWidth = Minecraft.getInstance().font.width(this.label);
        return Math.min(Math.max(16, availableWidth), 18 + labelWidth);
    }

    @Override
    public int measureHeight(int availableWidth, int availableHeight) {
        return 16;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int boxColor = this.isMouseOver(mouseX, mouseY) ? 0xFF3A373D : 0xFF27262A;
        graphics.fill(this.x(), this.y(), this.x() + 14, this.y() + 14, boxColor);
        graphics.renderOutline(this.x(), this.y(), 14, 14, 0xFF5A554C);
        if (this.checked) {
            graphics.fill(this.x() + 3, this.y() + 3, this.x() + 11, this.y() + 11, 0xFFD9B86C);
        }
        if (!this.label.getString().isBlank()) {
            Font font = Minecraft.getInstance().font;
            graphics.drawString(font, this.label, this.x() + 18, this.y() + 3, 0xFFF4F1EA, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0 || !this.isMouseOver(event.x(), event.y())) {
            return false;
        }
        this.checked = !this.checked;
        for (Consumer<Boolean> subscriber : this.subscribers) {
            subscriber.accept(this.checked);
        }
        return true;
    }
}
