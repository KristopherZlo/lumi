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
    private static final int FRAME_CONTROL_INSET = 8;
    private static final int ICON_BUTTON_WIDTH = 26;
    private static final int HEADER_CONTROL_GAP = 8;
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
    private LumiButton navigationButton;
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

    protected final void beginScreenInit() {
        initializeScreenScale();
        if (!(this instanceof LumiRecoveryScreen)) {
            boolean page = this instanceof LumiPageScreen;
            navigationButton = addIconButton(
                    navigationControlX(0, width), FRAME_CONTROL_INSET,
                    page ? "chevron-left" : "close",
                    Component.translatable(page
                            ? "luma.action.back" : "luma.action.close"),
                    this::onClose, LumiButton.Kind.NORMAL);
        }
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

    protected final void renderWindow(
            GuiGraphics graphics, int x, int y, int width, int height) {
        alignNavigation(x, y, width);
        graphics.fill(0, 0, this.width, this.height, LumiTheme.BACKDROP);
        LumiTheme.outlined(
                graphics, x, y, width, height,
                LumiTheme.WINDOW, LumiTheme.WINDOW_BORDER);
    }

    protected final void renderPage(
            GuiGraphics graphics, int x, int y, int width, int height) {
        alignNavigation(x, y, width);
        LumiTheme.outlined(
                graphics, x, y, width, height,
                LumiTheme.WINDOW, LumiTheme.WINDOW_BORDER);
        int headerBottom = Math.min(
                y + height - 1, y + LumiTheme.PAGE_HEADER_HEIGHT);
        if (width > 2 && headerBottom > y + 1) {
            graphics.fill(x + 1, y + 1, x + width - 1, headerBottom,
                    LumiTheme.TITLEBAR);
            graphics.fill(x + 1, headerBottom - 1,
                    x + width - 1, headerBottom,
                    LumiTheme.PANEL_BORDER);
        }
    }

    protected final void renderPanel(
            GuiGraphics graphics, int x, int y, int width, int height) {
        LumiTheme.outlined(
                graphics, x, y, width, height,
                LumiTheme.PANEL, LumiTheme.PANEL_BORDER);
    }

    protected final void renderPageHeader(
            GuiGraphics graphics, int x, int y, int width,
            Component heading, Component description) {
        int textX = x + 16;
        int right = x + width - 16;
        graphics.drawString(font, clippedHeader(heading, textX, right),
                textX, y + 14, LumiTheme.TEXT, false);
        if (description != null) {
            graphics.drawString(font, clippedHeader(description, textX, right),
                    textX, y + 29, LumiTheme.MUTED, false);
        }
    }

    protected final String clippedHeader(
            Component value, int textX, int contentRight) {
        return font.plainSubstrByWidth(
                value.getString(), headerTextWidth(
                        navigationControlX(), textX, contentRight));
    }

    protected final String clippedCenteredHeader(
            Component value, int centerX, int contentLeft, int contentRight) {
        return font.plainSubstrByWidth(value.getString(), centeredHeaderTextWidth(
                navigationControlX(), centerX, contentLeft, contentRight));
    }

    static int headerTextWidth(int controlX, int textX, int contentRight) {
        return Math.max(1, safeHeaderRight(controlX, contentRight) - textX);
    }

    static int centeredHeaderTextWidth(
            int controlX, int centerX, int contentLeft, int contentRight) {
        int safeRight = safeHeaderRight(controlX, contentRight);
        int radius = Math.min(centerX - contentLeft, safeRight - centerX);
        return Math.max(1, radius * 2);
    }

    static int navigationControlX(int frameX, int frameWidth) {
        return frameX + Math.max(
                0, frameWidth - FRAME_CONTROL_INSET - ICON_BUTTON_WIDTH);
    }

    protected final void alignNavigation(
            int frameX, int frameY, int frameWidth) {
        if (navigationButton == null) return;
        navigationButton.setX(navigationControlX(frameX, frameWidth));
        navigationButton.setY(frameY + FRAME_CONTROL_INSET);
    }

    private int navigationControlX() {
        return navigationButton == null
                ? navigationControlX(0, width) : navigationButton.getX();
    }

    private static int safeHeaderRight(int controlX, int contentRight) {
        return Math.min(contentRight, controlX - HEADER_CONTROL_GAP);
    }

    protected static Component errorText(String error) {
        return error.startsWith("luma.")
                ? Component.translatable(error) : Component.literal(error);
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
