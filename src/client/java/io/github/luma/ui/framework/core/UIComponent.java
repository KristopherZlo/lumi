package io.github.luma.ui.framework.core;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

public abstract class UIComponent implements GuiEventListener {

    private Sizing horizontalSizing = Sizing.content();
    private Sizing verticalSizing = Sizing.content();
    private int x;
    private int y;
    private int width;
    private int height;
    private boolean focused;

    public UIComponent sizing(Sizing horizontalSizing, Sizing verticalSizing) {
        this.horizontalSizing = horizontalSizing;
        this.verticalSizing = verticalSizing;
        return this;
    }

    public Sizing horizontalSizing() {
        return this.horizontalSizing;
    }

    public Sizing verticalSizing() {
        return this.verticalSizing;
    }

    public void layout(int x, int y, int availableWidth, int availableHeight) {
        int measuredWidth = this.measureWidth(availableWidth);
        int width = this.horizontalSizing.resolve(availableWidth, measuredWidth);
        int measuredHeight = this.measureHeight(width, availableHeight);
        int height = this.verticalSizing.resolve(availableHeight, measuredHeight);
        this.setBounds(x, y, width, height);
    }

    public int measureWidth(int availableWidth) {
        return Math.max(0, availableWidth);
    }

    public int measureHeight(int availableWidth, int availableHeight) {
        return Math.max(0, availableHeight);
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return false;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= this.x
                && mouseX < this.x + this.width
                && mouseY >= this.y
                && mouseY < this.y + this.height;
    }

    @Override
    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    @Override
    public boolean isFocused() {
        return this.focused;
    }

    public UIComponent focusedDescendant() {
        return this.focused ? this : null;
    }

    public void clearFocusDeep() {
        this.setFocused(false);
    }

    public int x() {
        return this.x;
    }

    public int y() {
        return this.y;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    protected void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        this.onBoundsChanged();
    }

    protected void onBoundsChanged() {
    }
}
