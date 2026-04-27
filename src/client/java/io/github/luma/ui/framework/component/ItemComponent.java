package io.github.luma.ui.framework.component;

import io.github.luma.ui.framework.core.Sizing;
import io.github.luma.ui.framework.core.UIComponent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

public final class ItemComponent extends UIComponent {

    private final ItemStack stack;

    ItemComponent(ItemStack stack) {
        this.stack = stack;
        this.sizing(Sizing.fixed(16), Sizing.fixed(16));
    }

    @Override
    public int measureWidth(int availableWidth) {
        return 16;
    }

    @Override
    public int measureHeight(int availableWidth, int availableHeight) {
        return 16;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!this.stack.isEmpty()) {
            graphics.renderItem(this.stack, this.x(), this.y());
        }
    }
}
