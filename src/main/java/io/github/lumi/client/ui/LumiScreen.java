package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.Window;
import io.github.lumi.LumiMod;
import io.github.lumi.client.onboarding.ClientContextualHelpHint;
import io.github.lumi.client.onboarding.ClientContextualHelpService;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Neutral V2 screen mechanics shared by pages and modal workflows. */
abstract class LumiScreen extends Screen {
    protected static final int INPUT_HEIGHT = 14;
    protected static final int INPUT_FRAME_HEIGHT = 18;
    private static final Identifier HINT_CLOSE_ICON = Identifier.fromNamespaceAndPath(
            LumiMod.MOD_ID, "textures/gui/icons/close.png");
    private final List<LumiScrollbar> scrollbars = new ArrayList<>();
    private final ClientContextualHelpService contextualHelp =
            new ClientContextualHelpService();
    private LumiUiScale uiScale = LumiUiScale.forFramebuffer(1280, 720);
    private boolean screenInitialized;
    private ClientContextualHelpHint contextualHint;
    private int hintX;
    private int hintY;
    private int hintWidth;
    private int hintHeight;
    private boolean handCursorActive;
    private static long handCursor;

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
        contextualHint = null;
        Window window = Minecraft.getInstance().getWindow();
        int currentGuiScale = currentGuiScale();
        uiScale = LumiUiScale.current();
        width = uiScale.virtualSize(window.getGuiScaledWidth(), currentGuiScale);
        height = uiScale.virtualSize(window.getGuiScaledHeight(), currentGuiScale);
    }

    protected final boolean addContextualHint(
            ClientContextualHelpHint hint, int x, int y, int width) {
        if (!contextualHelp.shouldShowHint(hint)) {
            return false;
        }
        contextualHint = hint;
        hintX = x;
        hintY = y;
        hintWidth = width;
        hintHeight = 30 + font.split(
                Component.translatable(hint.bodyKey()),
                Math.max(1, width - 14)).size() * 10;
        return true;
    }

    protected final int contextualHintOffset(int gap) {
        return contextualHint == null ? 0 : hintHeight + Math.max(0, gap);
    }

    protected final void moveContextualHint(int x, int y) {
        if (contextualHint != null) {
            hintX = x;
            hintY = y;
        }
    }

    protected final void resetContextualHints() {
        contextualHelp.resetHints();
        rebuildWidgets();
    }

    protected final void renderContextualHint(
            GuiGraphics graphics, int mouseX, int mouseY) {
        if (contextualHint == null) return;
        LumiTheme.outlined(
                graphics, hintX, hintY, hintWidth, hintHeight,
                LumiTheme.STATUS, LumiTheme.STATUS_BORDER);
        String title = font.plainSubstrByWidth(
                Component.translatable(contextualHint.titleKey()).getString(),
                Math.max(1, hintWidth - 44));
        graphics.drawString(font, title, hintX + 8, hintY + 8,
                LumiTheme.ACCENT, false);
        int lineY = hintY + 23;
        for (var line : font.split(
                Component.translatable(contextualHint.bodyKey()),
                Math.max(1, hintWidth - 14))) {
            graphics.drawString(font, line, hintX + 8, lineY,
                    LumiTheme.TEXT, false);
            lineY += 10;
        }
        int closeX = hintX + hintWidth - 26;
        boolean hovered = mouseX >= closeX && mouseX < closeX + 18
                && mouseY >= hintY + 6 && mouseY < hintY + 24;
        LumiTheme.outlined(
                graphics, closeX, hintY + 6, 18, 18,
                hovered ? LumiTheme.CHIP : LumiTheme.INSET,
                LumiTheme.STATUS_BORDER);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED, HINT_CLOSE_ICON,
                closeX + 3, hintY + 9, 0, 0, 12, 12,
                24, 24, 24, 24);
    }

    protected final boolean clickContextualHint(MouseButtonEvent click) {
        if (contextualHint == null
                || click.x() < hintX || click.x() >= hintX + hintWidth
                || click.y() < hintY || click.y() >= hintY + hintHeight) {
            return false;
        }
        int closeX = hintX + hintWidth - 26;
        if (click.x() >= closeX && click.x() < closeX + 18
                && click.y() >= hintY + 6 && click.y() < hintY + 24) {
            contextualHelp.dismissHint(contextualHint);
            rebuildWidgets();
        }
        return true;
    }

    protected final boolean contextualPointerHovered(int mouseX, int mouseY) {
        return contextualHint != null
                && mouseX >= hintX + hintWidth - 26
                && mouseX < hintX + hintWidth - 8
                && mouseY >= hintY + 6 && mouseY < hintY + 24;
    }

    @Override
    public void render(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderContextualHint(graphics, mouseX, mouseY);
        if (minecraft.screen == this) {
            updateCursor(mouseX, mouseY);
        }
    }

    protected boolean pointerHovered(int mouseX, int mouseY) {
        return children().stream().anyMatch(child ->
                child instanceof Button button
                        && button.isMouseOver(mouseX, mouseY))
                || contextualPointerHovered(mouseX, mouseY);
    }

    private void updateCursor(int mouseX, int mouseY) {
        boolean hovered = pointerHovered(mouseX, mouseY);
        if (hovered == handCursorActive) return;
        handCursorActive = hovered;
        if (hovered && handCursor == 0L) {
            handCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
        }
        GLFW.glfwSetCursor(
                Minecraft.getInstance().getWindow().handle(),
                hovered ? handCursor : 0L);
    }

    @Override
    public void removed() {
        if (handCursorActive) {
            GLFW.glfwSetCursor(
                    Minecraft.getInstance().getWindow().handle(), 0L);
            handCursorActive = false;
        }
        super.removed();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        MouseButtonEvent virtual = virtualClick(click);
        if (clickContextualHint(virtual)) return true;
        return super.mouseClicked(virtual, doubled);
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
