package io.github.lumi.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/** Classic arcade silhouettes kept separate from controls and simulation. */
final class LumiAstroInvadersSprites {
    private static final int GREEN = 0xff93e878;
    private static final int WHITE = 0xffe9eee7;
    private static final PixelSprite[][] INVADERS = {
            {
                    new PixelSprite(0b000010000, 0b000111000, 0b001111100, 0b011010110, 0b011111110, 0b001000100),
                    new PixelSprite(0b000010000, 0b000111000, 0b001111100, 0b011010110, 0b011111110, 0b010010010)
            },
            {
                    new PixelSprite(0b010000010, 0b001000100, 0b011111110, 0b110101011, 0b111111111, 0b010000010),
                    new PixelSprite(0b010000010, 0b001000100, 0b011111110, 0b110101011, 0b111111111, 0b101000101)
            },
            {
                    new PixelSprite(0b001111100, 0b011111110, 0b111111111, 0b110101011, 0b011111110, 0b010101010),
                    new PixelSprite(0b001111100, 0b011111110, 0b111111111, 0b110101011, 0b011111110, 0b101000101)
            }
    };
    private static final PixelSprite SHIP = new PixelSprite(
            0b000010000, 0b010010010, 0b010111010, 0b011111110, 0b111111111, 0b011000110);

    void drawInvader(GuiGraphics graphics, LumiAstroInvadersGame.Invader invader, int frame) {
        INVADERS[invader.row()][frame & 1].draw(
                graphics, Math.round(invader.x()), Math.round(invader.y()), 2, GREEN);
    }

    void drawShip(GuiGraphics graphics, int x, int y) {
        SHIP.draw(graphics, x, y, 2, WHITE);
    }

    void drawLives(GuiGraphics graphics, int lives) {
        for (int life = 0; life < 3; life++) {
            SHIP.draw(graphics, 282 + life * 12, 9, 1, life < lives ? GREEN : 0xff29362c);
        }
    }

    private record PixelSprite(int... rows) {
        void draw(GuiGraphics graphics, int centerX, int centerY, int scale, int color) {
            int left = centerX - 9 * scale / 2;
            int top = centerY - rows.length * scale / 2;
            for (int row = 0; row < rows.length; row++) {
                for (int column = 0; column < 9; column++) {
                    if ((rows[row] & (1 << (8 - column))) != 0) {
                        int x = left + column * scale, y = top + row * scale;
                        graphics.fill(x, y, x + scale, y + scale, color);
                    }
                }
            }
        }
    }
}
