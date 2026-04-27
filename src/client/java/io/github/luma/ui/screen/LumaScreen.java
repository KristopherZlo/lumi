package io.github.luma.ui.screen;

import io.github.luma.ui.framework.container.FlowLayout;
import io.github.luma.ui.framework.core.LumaUIAdapter;
import io.github.luma.ui.framework.core.UIComponent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Shared non-pausing base class for every in-world Luma screen.
 *
 * <p>Luma screens are overlays for singleplayer editing workflows, so they
 * must keep the world simulation, capture pipeline, and HUD updates running in
 * the background.
 */
public abstract class LumaScreen extends Screen {

    protected LumaUIAdapter<FlowLayout> uiAdapter;

    protected LumaScreen(Component title) {
        super(title);
    }

    protected abstract LumaUIAdapter<FlowLayout> createAdapter();

    protected abstract void build(FlowLayout root);

    @Override
    protected void init() {
        this.uiAdapter = this.createAdapter();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.uiAdapter != null) {
            this.uiAdapter.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public void tick() {
        super.tick();
        this.onLumaTick();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return this.uiAdapter != null && this.uiAdapter.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return this.uiAdapter != null
                && this.uiAdapter.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return this.uiAdapter != null && this.uiAdapter.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return this.uiAdapter != null && this.uiAdapter.charTyped(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    protected void setInitialFocus(UIComponent component) {
        if (this.uiAdapter != null) {
            this.uiAdapter.focus(component);
        }
    }

    protected void onLumaTick() {
    }
}
