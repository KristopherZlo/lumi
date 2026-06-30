package io.github.luma.ui.overlay;

import io.github.luma.ui.onboarding.KeyGlyph;
import io.github.luma.ui.onboarding.KeyGlyphResolver;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

public final class RoundedHudRenderer {

    public static final int TEXT = 0xFFF3F7FA;
    public static final int MUTED = 0xFFC9CED5;
    public static final int COMPACT_KEY_HEIGHT = 15;
    private static final int CARD_FILL = 0xD90B1016;
    private static final int CARD_BORDER = 0x803B4650;
    private static final int CHIP_FILL = 0xD20E1117;
    private static final int CHIP_BORDER = 0xB8A8B0BA;
    private static final int RADIUS = 4;
    private static final int CHIP_RADIUS = 3;

    private RoundedHudRenderer() {
    }

    public static void card(GuiGraphics graphics, int x, int y, int width, int height) {
        roundedRect(graphics, x, y, width, height, RADIUS, CARD_FILL, CARD_BORDER);
    }

    public static int keyWidth(KeyMapping key, String fallback) {
        return keyWidth(key, fallback, false);
    }

    public static int keyWidth(KeyMapping key, String fallback, boolean compact) {
        return KeyGlyphResolver.resolve(key)
                .map(glyph -> glyphWidth(glyph, compact))
                .orElseGet(() -> textChipWidth(keyLabel(key, fallback), compact));
    }

    public static int key(GuiGraphics graphics, KeyMapping key, int x, int y, String fallback) {
        return key(graphics, key, x, y, fallback, false);
    }

    public static int key(GuiGraphics graphics, KeyMapping key, int x, int y, String fallback, boolean compact) {
        return key(graphics, key, x, y, fallback, compact, false);
    }

    public static int key(
            GuiGraphics graphics,
            KeyMapping key,
            int x,
            int y,
            String fallback,
            boolean compact,
            boolean pressed
    ) {
        return KeyGlyphResolver.resolve(key)
                .map(glyph -> glyph(graphics, glyph, x, y, compact, pressed))
                .orElseGet(() -> textChip(graphics, keyLabel(key, fallback), x, y, compact));
    }

    private static int glyph(GuiGraphics graphics, KeyGlyph glyph, int x, int y, boolean compact, boolean pressed) {
        int frame = pressed ? 2 : 0;
        if (!compact) {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    glyph.textureId(),
                    x,
                    y,
                    frame * glyph.frameWidth(),
                    0,
                    glyph.frameWidth(),
                    glyph.height(),
                    glyph.frameWidth(),
                    glyph.height(),
                    glyph.textureWidth(),
                    glyph.height()
            );
            return glyph.frameWidth();
        }

        float scale = COMPACT_KEY_HEIGHT / (float) glyph.height();
        graphics.pose().pushMatrix();
        graphics.pose().scaleAround(scale, x, y);
        try {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    glyph.textureId(),
                    x,
                    y,
                    frame * glyph.frameWidth(),
                    0,
                    glyph.frameWidth(),
                    glyph.height(),
                    glyph.frameWidth(),
                    glyph.height(),
                    glyph.textureWidth(),
                    glyph.height()
            );
        } finally {
            graphics.pose().popMatrix();
        }
        return glyphWidth(glyph, true);
    }

    private static int glyphWidth(KeyGlyph glyph, boolean compact) {
        return compact
                ? Math.max(1, Math.round(glyph.frameWidth() * (COMPACT_KEY_HEIGHT / (float) glyph.height())))
                : glyph.frameWidth();
    }

    public static int textChipWidth(String text) {
        return textChipWidth(text, false);
    }

    public static int textChipWidth(String text, boolean compact) {
        Font font = Minecraft.getInstance().font;
        return Math.max(compact ? 13 : 19, font.width(text) + (compact ? 6 : 10));
    }

    public static int textChip(GuiGraphics graphics, String text, int x, int y) {
        return textChip(graphics, text, x, y, false);
    }

    public static int textChip(GuiGraphics graphics, String text, int x, int y, boolean compact) {
        Font font = Minecraft.getInstance().font;
        int width = textChipWidth(text, compact);
        int height = compact ? 15 : 21;
        roundedRect(graphics, x, y, width, height, CHIP_RADIUS, CHIP_FILL, CHIP_BORDER);
        graphics.drawString(font, text, x + ((width - font.width(text)) / 2), y + (compact ? 3 : 6), TEXT, false);
        return width;
    }

    private static String keyLabel(KeyMapping key, String fallback) {
        return KeyGlyphResolver.bracketedLabel(key, fallback);
    }

    public static void roundedRect(GuiGraphics graphics, int x, int y, int width, int height, int radius, int fill, int border) {
        fillRounded(graphics, x, y, width, height, radius, border);
        fillRounded(graphics, x + 1, y + 1, width - 2, height - 2, Math.max(1, radius - 1), fill);
    }

    private static void fillRounded(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int clampedRadius = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        for (int row = 0; row < height; row++) {
            int inset = roundedInset(row, height, clampedRadius);
            graphics.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
        }
    }

    private static int roundedInset(int row, int height, int radius) {
        if (radius <= 0) {
            return 0;
        }
        int topDistance = radius - row;
        int bottomDistance = row - (height - radius - 1);
        int distance = Math.max(topDistance, bottomDistance);
        if (distance <= 0) {
            return 0;
        }
        double inside = Math.max(0.0D, (radius * radius) - (distance * distance));
        return Math.max(0, (int) Math.ceil(radius - Math.sqrt(inside)));
    }
}
