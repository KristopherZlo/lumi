package io.github.luma.ui;

import io.github.luma.ui.framework.core.Sizing;
import io.github.luma.ui.framework.core.UIComponent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

public final class LumaScrollContainer<C extends UIComponent> extends UIComponent {

    private final C child;
    private int scrollbarThickness = 4;
    private int scrollStep = 20;
    private double scrollOffset;
    private double maxScroll;

    private LumaScrollContainer(Sizing horizontalSizing, Sizing verticalSizing, C child) {
        this.child = child;
        this.sizing(horizontalSizing, verticalSizing);
    }

    public static <C extends UIComponent> LumaScrollContainer<C> vertical(
            Sizing horizontalSizing,
            Sizing verticalSizing,
            C child
    ) {
        return new LumaScrollContainer<>(horizontalSizing, verticalSizing, child);
    }

    public LumaScrollContainer<C> scrollbarThiccness(int scrollbarThickness) {
        this.scrollbarThickness = Math.max(0, scrollbarThickness);
        return this;
    }

    public LumaScrollContainer<C> scrollStep(int scrollStep) {
        this.scrollStep = Math.max(1, scrollStep);
        return this;
    }

    @Override
    public void layout(int x, int y, int availableWidth, int availableHeight) {
        int width = this.horizontalSizing().resolve(availableWidth, availableWidth);
        int height = this.verticalSizing().resolve(availableHeight, availableHeight);
        this.setBounds(x, y, width, height);
        int contentWidth = Math.max(0, this.width() - this.scrollbarThickness - 1);
        int contentHeight = this.child.measureHeight(contentWidth, Integer.MAX_VALUE / 4);
        this.maxScroll = Math.max(0, contentHeight - this.height());
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, this.maxScroll));
        this.child.layout(this.x(), (int) Math.round(this.y() - this.scrollOffset), contentWidth, contentHeight);
    }

    @Override
    public int measureWidth(int availableWidth) {
        return Math.max(0, availableWidth);
    }

    @Override
    public int measureHeight(int availableWidth, int availableHeight) {
        return Math.max(0, availableHeight);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.enableScissor(this.x(), this.y(), this.x() + this.width(), this.y() + this.height());
        this.child.render(graphics, mouseX, mouseY, partialTick);
        graphics.disableScissor();
        this.renderScrollbar(graphics);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!this.isMouseOver(event.x(), event.y())) {
            return false;
        }
        return this.child.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!this.isMouseOver(mouseX, mouseY) || this.maxScroll <= 0) {
            return false;
        }
        this.scrollOffset = Math.max(0, Math.min(this.maxScroll, this.scrollOffset - verticalAmount * this.scrollStep));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return this.child.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return this.child.charTyped(event);
    }

    @Override
    public UIComponent focusedDescendant() {
        return this.child.focusedDescendant();
    }

    @Override
    public void clearFocusDeep() {
        this.child.clearFocusDeep();
    }

    public double progress() {
        if (this.maxScroll <= 0.0D) {
            return 0.0D;
        }
        return this.scrollOffset / this.maxScroll;
    }

    public LumaScrollContainer<C> restoreProgress(double progress) {
        double clampedProgress = Math.max(0.0D, Math.min(1.0D, progress));
        this.scrollOffset = this.maxScroll * clampedProgress;
        return this;
    }

    private void renderScrollbar(GuiGraphics graphics) {
        if (this.scrollbarThickness <= 0 || this.maxScroll <= 0.0D) {
            return;
        }
        int trackX = this.x() + this.width() - this.scrollbarThickness;
        graphics.fill(trackX, this.y(), this.x() + this.width(), this.y() + this.height(), 0x55000000);
        int thumbHeight = Math.max(12, (int) Math.round((double) this.height() * this.height()
                / (this.height() + this.maxScroll)));
        int maxThumbTravel = Math.max(1, this.height() - thumbHeight);
        int thumbY = this.y() + (int) Math.round(maxThumbTravel * this.progress());
        graphics.fill(trackX, thumbY, this.x() + this.width(), thumbY + thumbHeight, 0xAA6D6250);
    }
}
