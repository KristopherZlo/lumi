package io.github.luma.ui.screen;

import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.container.FlowLayout;
import net.minecraft.network.chat.Component;

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
    public boolean isPauseScreen() {
        return false;
    }

    protected void onLumaTick() {
    }
}
