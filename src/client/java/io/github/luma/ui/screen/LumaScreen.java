package io.github.luma.ui.screen;

import io.github.luma.ui.LumaUi;
import io.github.luma.ui.LumaScrollContainer;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.UIComponent;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
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

    private boolean openingAnimationPending = true;

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
            this.onClose();
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

    protected final void rebuildPreservingScroll(Supplier<? extends LumaScrollContainer<?>> scrollProvider) {
        this.rebuildPreservingScroll(scrollProvider, true);
    }

    protected final void rebuildPreservingScroll(
            Supplier<? extends LumaScrollContainer<?>> scrollProvider,
            boolean preserveScroll
    ) {
        double scrollProgress = preserveScroll ? scrollProgress(scrollProvider) : 0.0D;
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
        LumaScrollContainer<?> scroll = scrollProvider == null ? null : scrollProvider.get();
        if (scroll != null) {
            scroll.restoreProgress(scrollProgress);
        }
    }

    public Screen navigationParent() {
        return null;
    }

    protected void onLumaTick() {
    }

    protected final <T extends UIComponent> T animateOnFirstOpen(T component) {
        if (!this.openingAnimationPending) {
            return component;
        }
        this.openingAnimationPending = false;
        return LumaUi.animateOpen(component);
    }

    private static double scrollProgress(Supplier<? extends LumaScrollContainer<?>> scrollProvider) {
        LumaScrollContainer<?> scroll = scrollProvider == null ? null : scrollProvider.get();
        return scroll == null ? 0.0D : scroll.progress();
    }
}
