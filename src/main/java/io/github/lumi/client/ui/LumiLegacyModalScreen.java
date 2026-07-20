package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.Window;
import io.github.lumi.LumiMod;
import io.github.lumi.client.onboarding.ClientContextualHelpHint;
import io.github.lumi.client.onboarding.ClientContextualHelpService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Shared legacy window chrome for V2 modal workflows. */
abstract class LumiLegacyModalScreen extends Screen {
    protected static final int INPUT_HEIGHT = 14;
    protected static final int INPUT_FRAME_HEIGHT = 18;
    private static final int FRAME_CONTROL_INSET = 8;
    private static final int ICON_BUTTON_WIDTH = 26;
    private static final int HEADER_CONTROL_GAP = 8;
    private static final Identifier HINT_CLOSE_ICON = Identifier.fromNamespaceAndPath(
            LumiMod.MOD_ID, "textures/gui/icons/close.png");
    private final Screen background;
    private final ClientContextualHelpService contextualHelp =
            new ClientContextualHelpService();
    private LumiUiScale uiScale = LumiUiScale.forFramebuffer(1280, 720);
    private ClientContextualHelpHint contextualHint;
    private int hintX;
    private int hintY;
    private int hintWidth;
    private int hintHeight;
    private boolean legacyInitialized;
    private LumiLegacyButton navigationButton;
    private static long handCursor;

    protected LumiLegacyModalScreen(Component title) {
        this(Minecraft.getInstance().screen, title);
    }

    protected LumiLegacyModalScreen(Screen background, Component title) {
        super(title);
        this.background = background;
    }

    protected final LumiLegacyButton addLegacyButton(
            int x, int y, int width, Component label,
            Runnable action, LumiLegacyButton.Kind kind) {
        return addRenderableWidget(new LumiLegacyButton(
                x, y, width, 20, label, ignored -> action.run(), kind));
    }

    protected final LumiLegacyButton addLegacyContentButton(
            int x, int y, int maximumWidth, Component label,
            Runnable action, LumiLegacyButton.Kind kind) {
        return addLegacyButton(
                x, y, LumiLegacyButton.contentWidth(maximumWidth, label),
                label, action, kind);
    }

    protected final LumiLegacyButton addLegacyIconButton(
            int x, int y, String icon, Component label,
            Runnable action, LumiLegacyButton.Kind kind) {
        return addRenderableWidget(new LumiLegacyButton(
                x, y, 26, 20, label, ignored -> action.run(), kind, icon));
    }

    protected final void beginLegacyInit() {
        legacyInitialized = true;
        contextualHint = null;
        Window window = Minecraft.getInstance().getWindow();
        int currentGuiScale = currentGuiScale();
        uiScale = LumiUiScale.current();
        width = uiScale.virtualSize(window.getGuiScaledWidth(), currentGuiScale);
        height = uiScale.virtualSize(window.getGuiScaledHeight(), currentGuiScale);
        if (!(this instanceof LumiRecoveryScreen)) {
            boolean page = this instanceof LumiLegacyPageScreen;
            navigationButton = addLegacyIconButton(
                    navigationControlX(0, width), FRAME_CONTROL_INSET,
                    page ? "chevron-left" : "close",
                    Component.translatable(page
                            ? "luma.action.back" : "luma.action.close"),
                    this::onClose, LumiLegacyButton.Kind.NORMAL);
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
                Component.translatable(hint.bodyKey()), Math.max(1, width - 14)).size() * 10;
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (contextualHint != null) {
            LegacyLumiTheme.outlined(
                    graphics, hintX, hintY, hintWidth, hintHeight,
                    LegacyLumiTheme.STATUS, LegacyLumiTheme.STATUS_BORDER);
            String title = font.plainSubstrByWidth(
                    Component.translatable(contextualHint.titleKey()).getString(),
                    Math.max(1, hintWidth - 44));
            graphics.drawString(font, title, hintX + 8, hintY + 8,
                    LegacyLumiTheme.ACCENT, false);
            int lineY = hintY + 23;
            for (var line : font.split(
                    Component.translatable(contextualHint.bodyKey()),
                    Math.max(1, hintWidth - 14))) {
                graphics.drawString(font, line, hintX + 8, lineY,
                        LegacyLumiTheme.TEXT, false);
                lineY += 10;
            }
            int closeX = hintX + hintWidth - 26;
            boolean hovered = mouseX >= closeX && mouseX < closeX + 18
                    && mouseY >= hintY + 6 && mouseY < hintY + 24;
            LegacyLumiTheme.outlined(
                    graphics, closeX, hintY + 6, 18, 18,
                    hovered ? LegacyLumiTheme.CHIP : LegacyLumiTheme.INSET,
                    LegacyLumiTheme.STATUS_BORDER);
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED, HINT_CLOSE_ICON,
                    closeX + 3, hintY + 9, 0, 0, 12, 12,
                    24, 24, 24, 24);
        }
        updateCursor(mouseX, mouseY);
    }

    private void updateCursor(int mouseX, int mouseY) {
        boolean hovered = children().stream().anyMatch(child ->
                child instanceof Button button
                        && button.isMouseOver(mouseX, mouseY));
        if (hovered && handCursor == 0L) {
            handCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
        }
        GLFW.glfwSetCursor(
                Minecraft.getInstance().getWindow().handle(),
                hovered ? handCursor : 0L);
    }

    @Override
    public void removed() {
        GLFW.glfwSetCursor(Minecraft.getInstance().getWindow().handle(), 0L);
        super.removed();
    }

    protected final LegacyRenderContext beginLegacyRender(
            GuiGraphics graphics, int mouseX, int mouseY) {
        if (background != null && background != this
                && (!(background instanceof LumiLegacyModalScreen legacy)
                        || legacy.legacyInitialized)) {
            background.render(graphics, -1, -1, 0.0F);
        }
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
        MouseButtonEvent virtual = virtualClick(click);
        if (contextualHint != null
                && virtual.x() >= hintX && virtual.x() < hintX + hintWidth
                && virtual.y() >= hintY && virtual.y() < hintY + hintHeight) {
            int closeX = hintX + hintWidth - 26;
            if (virtual.x() >= closeX && virtual.x() < closeX + 18
                    && virtual.y() >= hintY + 6 && virtual.y() < hintY + 24) {
                contextualHelp.dismissHint(contextualHint);
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseClicked(virtual, doubled);
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
        alignLegacyNavigation(x, y, width);
        graphics.fill(0, 0, this.width, this.height, LegacyLumiTheme.BACKDROP);
        LegacyLumiTheme.outlined(
                graphics, x, y, width, height,
                LegacyLumiTheme.WINDOW, LegacyLumiTheme.WINDOW_BORDER);
    }

    protected final void renderLegacyPage(
            GuiGraphics graphics, int x, int y, int width, int height) {
        alignLegacyNavigation(x, y, width);
        LegacyLumiTheme.outlined(
                graphics, x, y, width, height,
                LegacyLumiTheme.WINDOW, LegacyLumiTheme.WINDOW_BORDER);
        int headerBottom = Math.min(
                y + height - 1, y + LegacyLumiTheme.PAGE_HEADER_HEIGHT);
        if (width > 2 && headerBottom > y + 1) {
            graphics.fill(x + 1, y + 1, x + width - 1, headerBottom,
                    LegacyLumiTheme.TITLEBAR);
            graphics.fill(x + 1, headerBottom - 1,
                    x + width - 1, headerBottom,
                    LegacyLumiTheme.PANEL_BORDER);
        }
    }

    protected final void renderLegacyPanel(
            GuiGraphics graphics, int x, int y, int width, int height) {
        LegacyLumiTheme.outlined(
                graphics, x, y, width, height,
                LegacyLumiTheme.PANEL, LegacyLumiTheme.PANEL_BORDER);
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
        return frameX + Math.max(0, frameWidth - FRAME_CONTROL_INSET - ICON_BUTTON_WIDTH);
    }

    protected final void alignLegacyNavigation(
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

    private MouseButtonEvent virtualClick(MouseButtonEvent click) {
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

    protected record LegacyRenderContext(int mouseX, int mouseY) { }
}
