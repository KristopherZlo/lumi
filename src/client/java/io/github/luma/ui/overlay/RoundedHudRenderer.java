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
    private static final int CARD_FILL = 0xD90B1016;
    private static final int CARD_BORDER = 0x803B4650;
    private static final int CHIP_FILL = 0xD20E1117;
    private static final int CHIP_BORDER = 0xB8A8B0BA;
    private static final int RADIUS = 8;
    private static final int CHIP_RADIUS = 4;

    private RoundedHudRenderer() {
    }

    public static void card(GuiGraphics graphics, int x, int y, int width, int height) {
        roundedRect(graphics, x, y, width, height, RADIUS, CARD_FILL, CARD_BORDER);
    }

    public static int keyWidth(KeyMapping key, String fallback) {
        return KeyGlyphResolver.resolve(key)
                .map(KeyGlyph::frameWidth)
                .orElseGet(() -> textChipWidth(fallback));
    }

    public static int key(GuiGraphics graphics, KeyMapping key, int x, int y, String fallback) {
        return KeyGlyphResolver.resolve(key)
                .map(glyph -> {
                    graphics.blit(
                            RenderPipelines.GUI_TEXTURED,
                            glyph.textureId(),
                            x,
                            y,
                            0,
                            0,
                            glyph.frameWidth(),
                            glyph.height(),
                            glyph.frameWidth(),
                            glyph.height(),
                            glyph.textureWidth(),
                            glyph.height()
                    );
                    return glyph.frameWidth();
                })
                .orElseGet(() -> textChip(graphics, fallback, x, y));
    }

    public static int textChipWidth(String text) {
        Font font = Minecraft.getInstance().font;
        return Math.max(19, font.width(text) + 10);
    }

    public static int textChip(GuiGraphics graphics, String text, int x, int y) {
        Font font = Minecraft.getInstance().font;
        int width = textChipWidth(text);
        roundedRect(graphics, x, y, width, 21, CHIP_RADIUS, CHIP_FILL, CHIP_BORDER);
        graphics.drawString(font, text, x + ((width - font.width(text)) / 2), y + 6, TEXT, false);
        return width;
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
