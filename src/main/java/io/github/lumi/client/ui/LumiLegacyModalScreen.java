package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Shared legacy window chrome for V2 modal workflows. */
abstract class LumiLegacyModalScreen extends Screen {
    private LumiUiScale uiScale = LumiUiScale.forFramebuffer(1280, 720);

    protected LumiLegacyModalScreen(Component title) {
        super(title);
    }

    protected final LumiLegacyButton addLegacyButton(
            int x, int y, int width, Component label,
            Runnable action, LumiLegacyButton.Kind kind) {
        return addRenderableWidget(new LumiLegacyButton(
                x, y, width, 20, label, ignored -> action.run(), kind));
    }

    protected final LumiLegacyButton addLegacyIconButton(
            int x, int y, String icon, Component label,
            Runnable action, LumiLegacyButton.Kind kind) {
        return addRenderableWidget(new LumiLegacyButton(
                x, y, 26, 20, label, ignored -> action.run(), kind, icon));
    }

    protected final void beginLegacyInit() {
        Window window = Minecraft.getInstance().getWindow();
        int currentGuiScale = currentGuiScale();
        uiScale = LumiUiScale.current();
        width = uiScale.virtualSize(window.getGuiScaledWidth(), currentGuiScale);
        height = uiScale.virtualSize(window.getGuiScaledHeight(), currentGuiScale);
    }

    protected final LegacyRenderContext beginLegacyRender(
            GuiGraphics graphics, int mouseX, int mouseY) {
        float scale = renderScale();
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);
        return new LegacyRenderContext(
                virtualCoordinate(mouseX), virtualCoordinate(mouseY));
    }

    protected final void endLegacyRender(GuiGraphics graphics) {
        graphics.pose().popMatrix();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        return super.mouseClicked(virtualClick(click), doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        return super.mouseReleased(virtualClick(click));
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return super.mouseScrolled(
                virtualCoordinate(mouseX), virtualCoordinate(mouseY),
                horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        float scale = renderScale();
        return super.mouseDragged(virtualClick(click), deltaX / scale, deltaY / scale);
    }

    protected final void renderLegacyWindow(
            GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(0, 0, this.width, this.height, LegacyLumiTheme.BACKDROP);
        LegacyLumiTheme.outlined(
                graphics, x, y, width, height,
                LegacyLumiTheme.WINDOW, LegacyLumiTheme.WINDOW_BORDER);
    }

    protected final void renderLegacyPanel(
            GuiGraphics graphics, int x, int y, int width, int height) {
        LegacyLumiTheme.outlined(
                graphics, x, y, width, height,
                LegacyLumiTheme.PANEL, LegacyLumiTheme.PANEL_BORDER);
    }

    protected static Component errorText(String error) {
        return error.startsWith("luma.")
                ? Component.translatable(error) : Component.literal(error);
    }

    private MouseButtonEvent virtualClick(MouseButtonEvent click) {
        return new MouseButtonEvent(
                virtualCoordinate(click.x()), virtualCoordinate(click.y()),
                click.buttonInfo());
    }

    private int virtualCoordinate(int coordinate) {
        return (int) Math.round(virtualCoordinate((double) coordinate));
    }

    private double virtualCoordinate(double coordinate) {
        return uiScale.virtualCoordinate(coordinate, currentGuiScale());
    }

    private float renderScale() {
        return uiScale.renderScale(currentGuiScale());
    }

    private static int currentGuiScale() {
        Window window = Minecraft.getInstance().getWindow();
        return window == null ? 1 : window.getGuiScale();
    }

    protected record LegacyRenderContext(int mouseX, int mouseY) { }
}
