package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Neutral V2 screen mechanics shared by pages and modal workflows. */
abstract class LumiScreen extends Screen {
    protected static final int INPUT_HEIGHT = 14;
    protected static final int INPUT_FRAME_HEIGHT = 18;
    private final List<LumiScrollbar> scrollbars = new ArrayList<>();
    private LumiUiScale uiScale = LumiUiScale.forFramebuffer(1280, 720);
    private boolean screenInitialized;

    protected LumiScreen(Component title) {
        super(title);
    }

    protected final LumiButton addButton(
            int x, int y, int width, Component label,
            Runnable action, LumiButton.Kind kind) {
        return addRenderableWidget(new LumiButton(
                x, y, width, 20, label, ignored -> action.run(), kind));
    }

    protected final LumiButton addContentButton(
            int x, int y, int maximumWidth, Component label,
            Runnable action, LumiButton.Kind kind) {
        return addButton(
                x, y, LumiButton.contentWidth(maximumWidth, label),
                label, action, kind);
    }

    protected final LumiButton addIconButton(
            int x, int y, String icon, Component label,
            Runnable action, LumiButton.Kind kind) {
        return addRenderableWidget(new LumiButton(
                x, y, 26, 20, label, ignored -> action.run(), kind, icon));
    }

    protected final LumiTextField addTextField(
            int x, int y, int width, Component label) {
        return addRenderableWidget(new LumiTextField(font, x, y, width, label));
    }

    protected final void renderTextField(
            GuiGraphics graphics, EditBox field) {
        if (!(field instanceof LumiTextField)) {
            LumiTheme.outlined(
                    graphics, field.getX() - 6, field.getY(),
                    field.getWidth() + 12, INPUT_FRAME_HEIGHT,
                    LumiTheme.INSET, LumiTheme.INSET_BORDER);
        }
    }

    protected final void renderScrollbar(
            GuiGraphics graphics,
            int viewportX,
            int y,
            int viewportWidth,
            int height,
            int totalExtent,
            int visibleExtent,
            int offset,
            IntConsumer update) {
        LumiScrollbar scrollbar = scrollbars.stream()
                .filter(candidate -> candidate.matches(
                        viewportX, y, viewportWidth, height))
                .findFirst()
                .orElseGet(() -> {
                    LumiScrollbar created = new LumiScrollbar(
                            viewportX, y, viewportWidth, height,
                            this::rebuildWidgets);
                    scrollbars.add(created);
                    return addRenderableWidget(created);
                });
        scrollbar.configure(totalExtent, visibleExtent, offset, update);
    }

    protected final void initializeScreenScale() {
        screenInitialized = true;
        scrollbars.clear();
        Window window = Minecraft.getInstance().getWindow();
        int currentGuiScale = currentGuiScale();
        uiScale = LumiUiScale.current();
        width = uiScale.virtualSize(window.getGuiScaledWidth(), currentGuiScale);
        height = uiScale.virtualSize(window.getGuiScaledHeight(), currentGuiScale);
    }

    final boolean screenInitialized() {
        return screenInitialized;
    }

    protected final ScaledRenderContext beginScaledRender(
            GuiGraphics graphics, int mouseX, int mouseY) {
        renderUnderlay(graphics);
        float scale = renderScale();
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);
        return new ScaledRenderContext(
                virtualCoordinate(mouseX), virtualCoordinate(mouseY));
    }

    protected void renderUnderlay(GuiGraphics graphics) {
    }

    protected final void endScaledRender(GuiGraphics graphics) {
        graphics.pose().popMatrix();
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        return super.mouseReleased(virtualClick(click));
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        return super.mouseScrolled(
                virtualCoordinate(mouseX), virtualCoordinate(mouseY),
                horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(
            MouseButtonEvent click, double deltaX, double deltaY) {
        float scale = renderScale();
        return super.mouseDragged(
                virtualClick(click), deltaX / scale, deltaY / scale);
    }

    protected final MouseButtonEvent virtualClick(MouseButtonEvent click) {
        return new MouseButtonEvent(
                virtualCoordinate(click.x()), virtualCoordinate(click.y()),
                click.buttonInfo());
    }

    private int virtualCoordinate(int coordinate) {
        return (int) Math.round(virtualCoordinate((double) coordinate));
    }

    protected final double virtualCoordinate(double coordinate) {
        return uiScale.virtualCoordinate(coordinate, currentGuiScale());
    }

    private float renderScale() {
        return uiScale.renderScale(currentGuiScale());
    }

    private static int currentGuiScale() {
        Window window = Minecraft.getInstance().getWindow();
        return window == null ? 1 : window.getGuiScale();
    }

    protected record ScaledRenderContext(int mouseX, int mouseY) { }
}
