package io.github.lumi.client.ui;

import java.util.Objects;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Hover-revealed scrollbar that reserves layout space and supports dragging. */
final class LumiScrollbar extends Button {
    static final int WIDTH = 3;
    static final int GUTTER_WIDTH = 7;
    private static final int MIN_THUMB_HEIGHT = 10;
    private final int viewportX;
    private final int viewportWidth;
    private final Runnable rebuild;
    private IntConsumer update = ignored -> { };
    private int totalExtent;
    private int visibleExtent;
    private int offset;
    private int dragOffset;
    private boolean dragging;

    LumiScrollbar(
            int viewportX, int viewportY, int viewportWidth, int viewportHeight,
            Runnable rebuild) {
        super(viewportX + viewportWidth - WIDTH, viewportY, WIDTH, viewportHeight,
                Component.translatable("luma.scrollbar"),
                ignored -> { }, DEFAULT_NARRATION);
        this.viewportX = viewportX;
        this.viewportWidth = viewportWidth;
        this.rebuild = Objects.requireNonNull(rebuild, "rebuild");
    }

    void configure(
            int totalExtent, int visibleExtent, int offset, IntConsumer update) {
        this.totalExtent = totalExtent;
        this.visibleExtent = visibleExtent;
        this.offset = Math.max(0, Math.min(offset, maximumOffset()));
        this.update = Objects.requireNonNull(update, "update");
        active = totalExtent > visibleExtent && visibleExtent > 0 && getHeight() > 0;
        visible = active;
    }

    boolean matches(int x, int y, int width, int height) {
        return viewportX == x && getY() == y
                && viewportWidth == width && getHeight() == height;
    }

    @Override
    protected void renderContents(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!dragging && !hoveredViewport(mouseX, mouseY)) return;
        graphics.fill(getX(), getY(), getX() + WIDTH,
                getY() + getHeight(), LumiTheme.INSET_BORDER);
        int thumbY = thumbY();
        graphics.fill(getX(), thumbY, getX() + WIDTH,
                thumbY + thumbHeight(), LumiTheme.ACCENT);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (!active || !isMouseOver(click.x(), click.y())) return false;
        int thumbY = thumbY();
        if (click.y() >= thumbY && click.y() < thumbY + thumbHeight()) {
            dragging = true;
            dragOffset = (int) click.y() - thumbY;
            setFocused(true);
        } else {
            moveTo(click.y(), thumbHeight() / 2);
            rebuild.run();
        }
        return true;
    }

    @Override
    public boolean mouseDragged(
            MouseButtonEvent click, double deltaX, double deltaY) {
        if (!dragging) return false;
        moveTo(click.y(), dragOffset);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (!dragging) return false;
        dragging = false;
        rebuild.run();
        return true;
    }

    private void moveTo(double mouseY, int grabbedOffset) {
        int replacement = offsetAt(
                getHeight(), totalExtent, visibleExtent,
                mouseY - getY(), grabbedOffset);
        if (replacement != offset) {
            offset = replacement;
            update.accept(replacement);
        }
    }

    private boolean hoveredViewport(int mouseX, int mouseY) {
        return mouseX >= viewportX && mouseX < viewportX + viewportWidth
                && mouseY >= getY() && mouseY < getY() + getHeight();
    }

    private int maximumOffset() {
        return Math.max(0, totalExtent - visibleExtent);
    }

    private int thumbHeight() {
        return thumbHeight(getHeight(), totalExtent, visibleExtent);
    }

    private int thumbY() {
        int maximum = maximumOffset();
        return getY() + (maximum == 0 ? 0 : (int) ((long)
                (getHeight() - thumbHeight()) * offset / maximum));
    }

    static int thumbHeight(int height, int totalExtent, int visibleExtent) {
        if (totalExtent <= 0) return height;
        return Math.min(height, Math.max(MIN_THUMB_HEIGHT,
                (int) ((long) height * visibleExtent / totalExtent)));
    }

    static int offsetAt(
            int height, int totalExtent, int visibleExtent,
            double pointerY, int grabbedOffset) {
        int maximum = Math.max(0, totalExtent - visibleExtent);
        int track = height - thumbHeight(height, totalExtent, visibleExtent);
        if (track <= 0) return 0;
        int replacement = (int) Math.round(
                (pointerY - grabbedOffset) * maximum / track);
        return Math.max(0, Math.min(maximum, replacement));
    }
}
