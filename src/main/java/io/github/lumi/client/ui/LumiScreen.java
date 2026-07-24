package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.Window;
import io.github.lumi.client.onboarding.ClientContextualHelpHint;
import io.github.lumi.client.onboarding.ClientContextualHelpService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Neutral V2 screen mechanics shared by pages and modal workflows. */
abstract class LumiScreen extends Screen {
    private static final int OPEN_MILLIS = 160;
    private static final float OPEN_SCALE = 0.94F;
    protected static final int INPUT_HEIGHT = 14;
    protected static final int INPUT_FRAME_HEIGHT = 18;
    private static final int FRAME_CONTROL_INSET = 8;
    private static final int ICON_BUTTON_WIDTH = 26;
    private static final int HEADER_CONTROL_GAP = 8;
    private static final int HINT_CONTROL_GAP = 2;
    private static final int MAX_HINTS_PER_GROUP = 3;
    private final List<LumiScrollbar> scrollbars = new ArrayList<>();
    private final ClientContextualHelpService contextualHelp =
            new ClientContextualHelpService();
    private LumiUiScale uiScale = new LumiUiScale(2);
    private boolean screenInitialized;
    private final LumiMotion opening = new LumiMotion();
    private boolean openingStarted;
    private boolean centeredOpening;
    private float openingValue = 1.0F;
    private int animationX;
    private int animationY;
    private int animationWidth;
    private int animationHeight;
    private List<ClientContextualHelpHint> contextualHints = List.of();
    private ClientContextualHelpHint contextualHint;
    private int contextualHintIndex;
    private int hintX;
    private int hintY;
    private int hintWidth;
    private int hintHeight;
    private boolean contextualHintsClosed;
    private LumiButton hintPreviousButton;
    private LumiButton hintNextButton;
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
        contextualHints = List.of();
        contextualHint = null;
        hintPreviousButton = null;
        hintNextButton = null;
        Window window = Minecraft.getInstance().getWindow();
        int currentGuiScale = currentGuiScale();
        uiScale = LumiUiScale.current();
        width = uiScale.virtualSize(window.getGuiScaledWidth(), currentGuiScale);
        height = uiScale.virtualSize(window.getGuiScaledHeight(), currentGuiScale);
    }

    protected final void beginScreenInit() {
        initializeScreenScale();
        if (!openingStarted) {
            openingStarted = true;
            centeredOpening = animateCenteredOpening();
            if (centeredOpening) {
                opening.start(OPEN_MILLIS);
            }
        }
        if (!(this instanceof LumiRecoveryScreen)) {
            boolean page = this instanceof LumiPageScreen;
            navigationButton = addIconButton(
                    navigationControlX(0, width), FRAME_CONTROL_INSET,
                    page ? "chevron-left" : "close",
                    Component.translatable(page
                            ? "luma.action.back" : "luma.action.close"),
                    this::onClose, LumiButton.Kind.NORMAL);
        }
        afterScreenInit();
    }

    protected void afterScreenInit() {
    }

    protected boolean animateCenteredOpening() {
        return false;
    }

    protected final boolean addContextualHint(
            ClientContextualHelpHint hint, int x, int y, int width) {
        return addContextualHints(List.of(hint), x, y, width);
    }

    protected final boolean addContextualHints(
            List<ClientContextualHelpHint> hints, int x, int y, int width) {
        if (contextualHintsClosed) {
            return false;
        }
        contextualHints = Objects.requireNonNull(hints, "hints").stream()
                .filter(contextualHelp::shouldShowHint)
                .limit(MAX_HINTS_PER_GROUP)
                .toList();
        if (contextualHints.isEmpty()) return false;
        contextualHintIndex = Math.min(
                contextualHintIndex, contextualHints.size() - 1);
        contextualHint = contextualHints.get(contextualHintIndex);
        hintX = x;
        hintY = y;
        hintWidth = width;
        hintHeight = 32 + contextualHints.stream()
                .mapToInt(value -> font.split(
                        Component.translatable(value.bodyKey()),
                        Math.max(1, width - 14)).size())
                .max().orElse(1) * 10;
        addHintControls();
        return true;
    }

    private void addHintControls() {
        int nextX = hintX + hintWidth - ICON_BUTTON_WIDTH - 6;
        int previousX = nextX - ICON_BUTTON_WIDTH - HINT_CONTROL_GAP;
        hintPreviousButton = addIconButton(
                previousX, hintY + 5, "chevron-left",
                Component.translatable("luma.action.back"),
                () -> showContextualHint(contextualHintIndex - 1),
                LumiButton.Kind.NORMAL);
        hintPreviousButton.active = contextualHintIndex > 0;
        boolean last = contextualHintIndex == contextualHints.size() - 1;
        hintNextButton = addIconButton(
                nextX, hintY + 5, last ? "close" : "chevron-right",
                Component.translatable(last
                        ? "luma.action.close" : "luma.action.next"),
                last ? this::dismissContextualHints
                        : () -> showContextualHint(contextualHintIndex + 1),
                LumiButton.Kind.NORMAL);
    }

    private void showContextualHint(int index) {
        contextualHintIndex = Math.max(
                0, Math.min(index, contextualHints.size() - 1));
        rebuildWidgets();
    }

    private void dismissContextualHints() {
        contextualHelp.dismissHints(contextualHints);
        contextualHintsClosed = true;
        contextualHintIndex = 0;
        rebuildWidgets();
    }

    protected final int contextualHintOffset(int gap) {
        return contextualHint == null ? 0 : hintHeight + Math.max(0, gap);
    }

    protected final void moveContextualHint(int x, int y) {
        if (contextualHint != null) {
            int deltaX = x - hintX;
            int deltaY = y - hintY;
            hintX = x;
            hintY = y;
            hintPreviousButton.setX(hintPreviousButton.getX() + deltaX);
            hintPreviousButton.setY(hintPreviousButton.getY() + deltaY);
            hintNextButton.setX(hintNextButton.getX() + deltaX);
            hintNextButton.setY(hintNextButton.getY() + deltaY);
        }
    }

    protected final void resetContextualHints() {
        contextualHelp.resetHints();
        contextualHintsClosed = false;
        contextualHintIndex = 0;
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
                hintTitleWidth());
        graphics.drawString(font, title, hintX + 8, hintY + 8,
                LumiTheme.ACCENT, false);
        String counter = (contextualHintIndex + 1)
                + "/" + contextualHints.size();
        int counterRight = hintPreviousButton.getX() - 6;
        graphics.drawString(
                font, counter, counterRight - font.width(counter),
                hintY + 10, LumiTheme.MUTED, false);
        int lineY = hintY + 27;
        for (var line : font.split(
                Component.translatable(contextualHint.bodyKey()),
                Math.max(1, hintWidth - 14))) {
            graphics.drawString(font, line, hintX + 8, lineY,
                    LumiTheme.TEXT, false);
            lineY += 10;
        }
    }

    private int hintTitleWidth() {
        String counter = (contextualHintIndex + 1)
                + "/" + contextualHints.size();
        return Math.max(1, hintPreviousButton.getX() - 12
                - font.width(counter) - (hintX + 8));
    }

    protected final boolean clickContextualHint(MouseButtonEvent click) {
        if (contextualHint == null
                || click.x() < hintX || click.x() >= hintX + hintWidth
                || click.y() < hintY || click.y() >= hintY + hintHeight) {
            return false;
        }
        return true;
    }

    protected final void renderWindow(
            GuiGraphics graphics, int x, int y, int width, int height) {
        animationFrame(x, y, width, height);
        alignNavigation(x, y, width);
        LumiTheme.outlined(
                graphics, x, y, width, height,
                LumiTheme.WINDOW, LumiTheme.WINDOW_BORDER);
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
        renderContextualHint(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (minecraft.screen == this) {
            updateCursor(mouseX, mouseY);
        }
    }

    protected boolean pointerHovered(int mouseX, int mouseY) {
        return children().stream().anyMatch(child ->
                child instanceof Button button
                        && button.isMouseOver(mouseX, mouseY));
    }

    private void updateCursor(int mouseX, int mouseY) {
        boolean hovered = !interactionBlocked()
                && pointerHovered(mouseX, mouseY);
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
        if (interactionBlocked()) return true;
        MouseButtonEvent virtual = virtualClick(click);
        if (super.mouseClicked(virtual, doubled)) return true;
        return clickContextualHint(virtual);
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
        animationWidth = 0;
        openingValue = centeredOpening ? opening.value() : 1.0F;
        if (openingValue < 1.0F) {
            float openingScale = OPEN_SCALE
                    + (1.0F - OPEN_SCALE) * openingValue;
            graphics.pose().translate(width / 2.0F, height / 2.0F);
            graphics.pose().scale(openingScale, openingScale);
            graphics.pose().translate(-width / 2.0F, -height / 2.0F);
        }
        renderScaledUnderlay(graphics);
        beginScaledContent(graphics);
        return new ScaledRenderContext(
                virtualCoordinate(mouseX), virtualCoordinate(mouseY));
    }

    protected void renderUnderlay(GuiGraphics graphics) {
    }

    protected void renderScaledUnderlay(GuiGraphics graphics) {
    }

    protected void beginScaledContent(GuiGraphics graphics) {
    }

    protected final void endScaledRender(GuiGraphics graphics) {
        endScaledContent(graphics);
        if (openingValue < 1.0F && animationWidth > 0) {
            graphics.fill(
                    animationX, animationY,
                    animationX + animationWidth, animationY + animationHeight,
                    LumiTheme.withOpacity(
                            LumiTheme.BACKDROP, 1.0F - openingValue));
        }
        graphics.pose().popMatrix();
    }

    protected void endScaledContent(GuiGraphics graphics) {
    }

    protected final void animationFrame(
            int x, int y, int width, int height) {
        animationX = x;
        animationY = y;
        animationWidth = width;
        animationHeight = height;
    }

    protected final boolean openingAnimationRunning() {
        return centeredOpening && opening.running();
    }

    protected boolean interactionBlocked() {
        return openingAnimationRunning();
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (interactionBlocked()) return true;
        return super.mouseReleased(virtualClick(click));
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        if (interactionBlocked()) return true;
        return super.mouseScrolled(
                virtualCoordinate(mouseX), virtualCoordinate(mouseY),
                horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(
            MouseButtonEvent click, double deltaX, double deltaY) {
        if (interactionBlocked()) return true;
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
