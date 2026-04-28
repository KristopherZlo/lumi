package io.github.luma.ui.screen;

import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.container.FlowLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Shared non-pausing base class for every in-world Luma screen.
 *
 * <p>Luma screens are overlays for singleplayer editing workflows, so they
 * must keep the world simulation, capture pipeline, and HUD updates running in
 * the background.
 */
public abstract class LumaScreen extends BaseOwoScreen<FlowLayout> {

    protected LumaScreen(Component title) {
        super(title);
    }

    @Override
    public void tick() {
        super.tick();
        this.onLumaTick();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.closeLumaUi();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    protected void closeLumaUi() {
        Minecraft.getInstance().setScreen(null);
    }

    protected void onLumaTick() {
    }
}
