package io.github.lumi.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared window chrome for V2 modal workflows. */
abstract class LumiModalScreen extends LumiScreen {
    private static final int FRAME_CONTROL_INSET = 8;
    private static final int ICON_BUTTON_WIDTH = 26;
    private static final int HEADER_CONTROL_GAP = 8;
    private final Screen background;
    private LumiButton navigationButton;

    protected LumiModalScreen(Component title) {
        this(Minecraft.getInstance().screen, title);
    }

    protected LumiModalScreen(Screen background, Component title) {
        super(title);
        this.background = background;
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

    @Override
    protected void renderUnderlay(GuiGraphics graphics) {
        if (background != null && background != this
                && (!(background instanceof LumiScreen screen)
                        || screen.screenInitialized())) {
            background.render(graphics, -1, -1, 0.0F);
        }
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
        return frameX + Math.max(0, frameWidth - FRAME_CONTROL_INSET - ICON_BUTTON_WIDTH);
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

}
