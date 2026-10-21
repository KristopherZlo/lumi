package io.github.lumi.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** One idle-only label; input dismisses it without changing the underlying screen. */
final class LumiDvdScreensaver {
    private static final long IDLE_MILLIS = 5_000L;
    private static final int FADE_MILLIS = 600;
    private static final String LABEL = "LUMI";
    private static final int[] COLORS = {0xff8ecfff, 0xffffa4cb, 0xffb6eba2, 0xffffd480, 0xffbea7ff};
    private long lastActivity = Long.MIN_VALUE, lastFrame;
    private int mouseX = Integer.MIN_VALUE, mouseY = Integer.MIN_VALUE, color;
    private float x = 8, y = 8, velocityX = 36, velocityY = 26;
    private float opacity;
    private boolean visible;

    void activity(long now) {
        lastActivity = lastFrame = now;
        visible = false;
        opacity = 0;
    }

    boolean allowInput(long now) {
        boolean allowed = !visible;
        activity(now);
        return allowed;
    }

    void render(GuiGraphics graphics, Font font, int width, int height, int pointerX, int pointerY) {
        if (!advance(System.currentTimeMillis(), pointerX, pointerY, width, height,
                font.width(LABEL) * 2, font.lineHeight * 2, LumiMotion.enabled()) || opacity < .02F) return;
        graphics.fill(0, 0, width, height, LumiTheme.withOpacity(0xff101114, opacity * .96F));
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(2, 2);
        graphics.drawString(font, LABEL, 0, 0, LumiTheme.withOpacity(color(), opacity), false);
        graphics.pose().popMatrix();
    }

    boolean advance(long now, int pointerX, int pointerY, int width, int height,
            int labelWidth, int labelHeight, boolean motion) {
        if (lastActivity == Long.MIN_VALUE || pointerX != mouseX || pointerY != mouseY || !motion) {
            activity(now);
            mouseX = pointerX;
            mouseY = pointerY;
        }
        float seconds = Math.clamp((now - lastFrame) / 1_000F, 0, .1F);
        lastFrame = now;
        if (!motion || now - lastActivity < IDLE_MILLIS) return false;
        if (!visible) { x = y = 8; visible = true; }
        float progress = Math.clamp((now - lastActivity - IDLE_MILLIS) / (float) FADE_MILLIS, 0, 1);
        opacity = progress * progress * (3 - 2 * progress);
        float right = Math.max(0, width - labelWidth), bottom = Math.max(0, height - labelHeight);
        x += velocityX * seconds;
        y += velocityY * seconds;
        boolean bounce = false;
        if (x < 0 || x > right) { x = Math.clamp(x, 0, right); velocityX = -velocityX; bounce = true; }
        if (y < 0 || y > bottom) { y = Math.clamp(y, 0, bottom); velocityY = -velocityY; bounce = true; }
        if (bounce) color = (color + 1) % COLORS.length;
        return true;
    }

    float x() { return x; }
    float y() { return y; }
    int color() { return COLORS[color]; }
    float opacity() { return opacity; }
}
