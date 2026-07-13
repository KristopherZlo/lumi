package io.github.luma.ui.screen;

import com.mojang.blaze3d.platform.Window;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUiScale;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.UIComponent;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
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
    private LumaUiScale uiScale = LumaUiScale.forFramebuffer(1280, 720);

    protected LumaScreen(Component title) {
        super(title);
    }

    @Override
    protected void init() {
        int currentGuiScale = this.currentGuiScale();
        this.uiScale = LumaUiScale.current();
        this.width = this.uiScale.virtualSize(this.width, currentGuiScale);
        this.height = this.uiScale.virtualSize(this.height, currentGuiScale);
        super.init();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        float scale = this.lumaUiScale();
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);
        try {
            super.render(graphics, this.virtualCoordinate(mouseX), this.virtualCoordinate(mouseY), partialTick);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    @Override
    protected void drawComponentTooltip(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.uiAdapter == null) {
            return;
        }
        int virtualMouseX = this.virtualCoordinate(mouseX);
        int virtualMouseY = this.virtualCoordinate(mouseY);
        UIComponent hovered = this.uiAdapter.rootComponent.childAt(virtualMouseX, virtualMouseY);
        while (hovered != null && hovered != this.uiAdapter.rootComponent) {
            if (hovered.shouldDrawTooltip(virtualMouseX, virtualMouseY)) {
                graphics.pose().pushMatrix();
                graphics.pose().scaleAround(this.lumaUiScale(), mouseX, mouseY);
                try {
                    graphics.renderTooltip(
                            Minecraft.getInstance().font,
                            hovered.tooltip(),
                            mouseX,
                            mouseY,
                            DefaultTooltipPositioner.INSTANCE,
                            null
                    );
                } finally {
                    graphics.pose().popMatrix();
                }
                return;
            }
            hovered = hovered.parent();
        }
    }

    @Override
    public void tick() {
        super.tick();
        this.onLumaTick();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        return super.mouseClicked(this.virtualClick(click), doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        return super.mouseReleased(this.virtualClick(click));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return super.mouseScrolled(
                this.virtualCoordinate(mouseX),
                this.virtualCoordinate(mouseY),
                horizontalAmount,
                verticalAmount
        );
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        float scale = this.lumaUiScale();
        return super.mouseDragged(this.virtualClick(click), deltaX / scale, deltaY / scale);
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

    private MouseButtonEvent virtualClick(MouseButtonEvent click) {
        return new MouseButtonEvent(
                this.virtualCoordinate(click.x()),
                this.virtualCoordinate(click.y()),
                click.buttonInfo()
        );
    }

    private int virtualCoordinate(int coordinate) {
        return (int) Math.round(this.virtualCoordinate((double) coordinate));
    }

    private double virtualCoordinate(double coordinate) {
        return this.uiScale.virtualCoordinate(coordinate, this.currentGuiScale());
    }

    private float lumaUiScale() {
        return this.uiScale.renderScale(this.currentGuiScale());
    }

    private int currentGuiScale() {
        Window window = Minecraft.getInstance().getWindow();
        return window == null ? 1 : window.getGuiScale();
    }
}
