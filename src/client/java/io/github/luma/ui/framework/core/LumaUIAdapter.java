package io.github.luma.ui.framework.core;

import io.github.luma.ui.framework.container.FlowLayout;
import io.github.luma.ui.screen.LumaScreen;
import java.util.function.BiFunction;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

public final class LumaUIAdapter<R extends FlowLayout> {

    public final R rootComponent;
    private final LumaScreen screen;
    private UIComponent focusedComponent;

    private LumaUIAdapter(LumaScreen screen, R rootComponent) {
        this.screen = screen;
        this.rootComponent = rootComponent;
    }

    public static LumaUIAdapter<FlowLayout> create(
            LumaScreen screen,
            BiFunction<Sizing, Sizing, FlowLayout> rootFactory
    ) {
        return new LumaUIAdapter<>(screen, rootFactory.apply(Sizing.fill(100), Sizing.fill(100)));
    }

    public void inflateAndMount() {
        this.layout();
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.layout();
        this.rootComponent.render(graphics, mouseX, mouseY, partialTick);
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        this.rootComponent.clearFocusDeep();
        boolean handled = this.rootComponent.mouseClicked(event, doubleClick);
        this.focusedComponent = this.rootComponent.focusedDescendant();
        return handled;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return this.rootComponent.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    public boolean keyPressed(KeyEvent event) {
        UIComponent focused = this.focusedComponent == null
                ? this.rootComponent.focusedDescendant()
                : this.focusedComponent;
        return focused != null && focused.keyPressed(event);
    }

    public boolean charTyped(CharacterEvent event) {
        UIComponent focused = this.focusedComponent == null
                ? this.rootComponent.focusedDescendant()
                : this.focusedComponent;
        return focused != null && focused.charTyped(event);
    }

    public void focus(UIComponent component) {
        this.rootComponent.clearFocusDeep();
        component.setFocused(true);
        this.focusedComponent = component;
    }

    private void layout() {
        this.rootComponent.layout(0, 0, this.screen.width, this.screen.height);
    }
}
