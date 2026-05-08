package io.github.luma.ui.onboarding;

import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Draws a screen dimmer with a live cut-out around a workspace component and a
 * compact onboarding prompt positioned near that target.
 */
public final class OnboardingSpotlightOverlay extends BaseUIComponent {

    private static final int DIM_COLOR = 0xB8000000;
    private static final int PANEL_COLOR = 0xF0171B1E;
    private static final int PANEL_BORDER = 0xFF3B4147;
    private static final int ACCENT = 0xFF4ADE80;
    private static final int TEXT = 0xFFF3F7FA;
    private static final int MUTED = 0xFF98A6B3;
    private static final int DISABLED = 0xFF5E6872;
    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_PADDING = 8;
    private static final int BUTTON_HEIGHT = 18;
    private static final int BUTTON_WIDTH = 56;
    private static final int CLOSE_BUTTON_SIZE = 18;
    private static final int MAX_HELP_LINES = 3;

    private final Supplier<UIComponent> targetSupplier;
    private final OnboardingTour tour;
    private final Consumer<OnboardingTour.Transition> actions;
    private int backX;
    private int backY;
    private int nextX;
    private int nextY;
    private int closeX;
    private int closeY;

    public OnboardingSpotlightOverlay(
            Supplier<UIComponent> targetSupplier,
            OnboardingTour tour,
            Consumer<OnboardingTour.Transition> actions
    ) {
        this.targetSupplier = targetSupplier == null ? () -> null : targetSupplier;
        this.tour = tour;
        this.actions = actions == null ? ignored -> { } : actions;
        this.sizing(Sizing.fill(100), Sizing.fill(100));
    }

    @Override
    protected int determineHorizontalContentSize(Sizing sizing) {
        return PANEL_WIDTH;
    }

    @Override
    protected int determineVerticalContentSize(Sizing sizing) {
        return 120;
    }

    @Override
    public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
        UIComponent target = this.targetSupplier.get();
        Rect hole = this.hole(target);
        if (hole == null) {
            graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, DIM_COLOR);
        } else {
            this.drawDimmer(graphics, hole);
            graphics.drawRectOutline(hole.x(), hole.y(), hole.width(), hole.height(), ACCENT);
        }
        this.drawPrompt(graphics, hole);
        this.cursorStyle(this.hoveringClickable(mouseX, mouseY) ? CursorStyle.HAND : CursorStyle.NONE);
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
        int absoluteX = this.x + (int) click.x();
        int absoluteY = this.y + (int) click.y();
        if (this.inside(absoluteX, absoluteY, this.backX, this.backY, BUTTON_WIDTH, BUTTON_HEIGHT)
                && this.tour.canGoBack()) {
            this.actions.accept(this.tour.back());
            return true;
        }
        if (this.inside(absoluteX, absoluteY, this.closeX, this.closeY, CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE)) {
            this.actions.accept(OnboardingTour.Transition.COMPLETE);
            return true;
        }
        if (this.inside(absoluteX, absoluteY, this.nextX, this.nextY, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            this.actions.accept(this.tour.next());
            return true;
        }
        return true;
    }

    @Override
    public boolean onMouseUp(MouseButtonEvent click) {
        return true;
    }

    @Override
    public boolean onMouseScroll(double mouseX, double mouseY, double amount) {
        return true;
    }

    @Override
    public boolean onMouseDrag(MouseButtonEvent click, double deltaX, double deltaY) {
        return true;
    }

    private void drawDimmer(OwoUIGraphics graphics, Rect hole) {
        int left = this.x;
        int top = this.y;
        int right = this.x + this.width;
        int bottom = this.y + this.height;
        graphics.fill(left, top, right, hole.y(), DIM_COLOR);
        graphics.fill(left, hole.bottom(), right, bottom, DIM_COLOR);
        graphics.fill(left, hole.y(), hole.x(), hole.bottom(), DIM_COLOR);
        graphics.fill(hole.right(), hole.y(), right, hole.bottom(), DIM_COLOR);
    }

    private void drawPrompt(OwoUIGraphics graphics, Rect hole) {
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        int textWidth = PANEL_WIDTH - (PANEL_PADDING * 2);
        java.util.List<FormattedCharSequence> helpLines = font.split(this.tour.helpText(), textWidth)
                .stream()
                .limit(MAX_HELP_LINES)
                .toList();
        int panelHeight = PANEL_PADDING + 11 + 12 + (helpLines.size() * 10) + 7 + BUTTON_HEIGHT + PANEL_PADDING;
        int panelX = this.x + Math.max(8, (this.width - PANEL_WIDTH) / 2);
        int panelY = this.y + 16;
        if (hole != null) {
            panelX = this.clamp(hole.centerX() - (PANEL_WIDTH / 2), this.x + 8, this.x + this.width - PANEL_WIDTH - 8);
            panelY = hole.y() - panelHeight - 8;
            if (panelY < this.y + 8) {
                panelY = hole.bottom() + 8;
            }
            panelY = this.clamp(panelY, this.y + 8, this.y + this.height - panelHeight - 8);
        }

        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, PANEL_COLOR);
        graphics.drawRectOutline(panelX, panelY, PANEL_WIDTH, panelHeight, PANEL_BORDER);

        int textX = panelX + PANEL_PADDING;
        int cursorY = panelY + PANEL_PADDING;
        this.closeX = panelX + PANEL_WIDTH - PANEL_PADDING - CLOSE_BUTTON_SIZE;
        this.closeY = panelY + PANEL_PADDING - 1;
        this.drawButton(
                graphics,
                font,
                this.closeX,
                this.closeY,
                CLOSE_BUTTON_SIZE,
                Component.translatable("luma.action.close_onboarding"),
                true
        );

        graphics.drawString(font, this.tour.headerText(), textX, cursorY, MUTED, false);
        cursorY += 11;
        graphics.drawString(font, this.tour.pageName(), textX, cursorY, ACCENT, false);
        cursorY += 12;
        for (FormattedCharSequence line : helpLines) {
            graphics.drawString(font, line, textX, cursorY, TEXT, false);
            cursorY += 10;
        }

        this.backX = panelX + PANEL_PADDING;
        this.backY = panelY + panelHeight - BUTTON_HEIGHT - PANEL_PADDING;
        this.nextX = panelX + PANEL_WIDTH - BUTTON_WIDTH - PANEL_PADDING;
        this.nextY = this.backY;
        this.drawButton(
                graphics,
                font,
                this.backX,
                this.backY,
                BUTTON_WIDTH,
                Component.translatable("luma.action.back"),
                this.tour.canGoBack()
        );
        this.drawButton(
                graphics,
                font,
                this.nextX,
                this.nextY,
                BUTTON_WIDTH,
                Component.translatable("luma.action.next"),
                true
        );
    }

    private void drawButton(
            OwoUIGraphics graphics,
            Font font,
            int x,
            int y,
            int width,
            Component label,
            boolean active
    ) {
        int fill = active ? 0xFF252B30 : 0xFF191D21;
        int border = active ? PANEL_BORDER : 0xFF25282B;
        int textColor = active ? TEXT : DISABLED;
        graphics.fill(x, y, x + width, y + BUTTON_HEIGHT, fill);
        graphics.drawRectOutline(x, y, width, BUTTON_HEIGHT, border);
        graphics.drawString(
                font,
                label,
                x + ((width - font.width(label)) / 2),
                y + 5,
                textColor,
                false
        );
    }

    private Rect hole(UIComponent target) {
        if (target == null || target.width() <= 0 || target.height() <= 0) {
            return null;
        }
        int padding = 5;
        int x1 = this.clamp(target.x() - padding, this.x, this.x + this.width);
        int y1 = this.clamp(target.y() - padding, this.y, this.y + this.height);
        int x2 = this.clamp(target.x() + target.width() + padding, this.x, this.x + this.width);
        int y2 = this.clamp(target.y() + target.height() + padding, this.y, this.y + this.height);
        return new Rect(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1));
    }

    private boolean inside(int x, int y, int rectX, int rectY, int rectWidth, int rectHeight) {
        return x >= rectX && x < rectX + rectWidth && y >= rectY && y < rectY + rectHeight;
    }

    private boolean hoveringClickable(int mouseX, int mouseY) {
        boolean back = this.tour.canGoBack()
                && this.inside(mouseX, mouseY, this.backX, this.backY, BUTTON_WIDTH, BUTTON_HEIGHT);
        boolean next = this.inside(mouseX, mouseY, this.nextX, this.nextY, BUTTON_WIDTH, BUTTON_HEIGHT);
        boolean close = this.inside(mouseX, mouseY, this.closeX, this.closeY, CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE);
        return back || next || close;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Rect(int x, int y, int width, int height) {
        private int right() {
            return this.x + this.width;
        }

        private int bottom() {
            return this.y + this.height;
        }

        private int centerX() {
            return this.x + (this.width / 2);
        }
    }
}
