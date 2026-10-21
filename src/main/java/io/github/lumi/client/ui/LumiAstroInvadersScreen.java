package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Pixel arcade launched only from the shortcut guide's secret code. */
final class LumiAstroInvadersScreen extends LumiModalScreen {
    private static final int[] ALIEN = {0b0011100, 0b0111110, 0b1101011, 0b1111111, 0b0100010};
    private static final int[] SHIP = {0b0001000, 0b0011100, 0b0111110, 0b1111111, 0b1100011};
    private static final int[] COLORS = {0xffbea7ff, 0xff8ecfff, 0xffb6eba2};
    private final Screen parent;
    private final LumiAstroInvadersGame game = new LumiAstroInvadersGame();
    private boolean left, right, firing, pauseHeld;
    private long lastFrame;

    LumiAstroInvadersScreen(Screen parent) {
        super(parent, Component.literal("Lumi Astro Invaders"));
        this.parent = parent;
    }

    @Override protected void init() {
        beginScreenInit();
        left = right = firing = pauseHeld = false;
        lastFrame = System.nanoTime();
    }

    @Override protected void renderUnderlay(GuiGraphics graphics) {
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), LumiTheme.BACKDROP);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        if (!minecraft.isWindowActive()) left = right = firing = pauseHeld = false;
        else if (minecraft.screen == this) game.advance((now - lastFrame) / 1_000_000_000F, left, right, firing);
        lastFrame = now;
        var pointer = beginScaledRender(graphics, mouseX, mouseY);
        try {
            float scale = Math.max(.1F, Math.min(1.5F, Math.min((width - 24) / 320F, (height - 94) / 200F)));
            int boardWidth = Math.round(320 * scale), boardHeight = Math.round(200 * scale);
            int x = (width - boardWidth) / 2, y = Math.max(44, (height - boardHeight) / 2);
            renderWindow(graphics, x - 7, y - 27, boardWidth + 14, boardHeight + 70);
            graphics.drawString(font, title, x, y - 18, LumiTheme.TEXT, false);
            var controls = font.split(Component.translatable("lumi.astro.controls"), boardWidth);
            for (int line = 0; line < controls.size(); line++) {
                var text = controls.get(line);
                graphics.drawString(font, text, (width - font.width(text)) / 2,
                        y + boardHeight + 7 + line * 10, LumiTheme.MUTED, false);
            }
            graphics.pose().pushMatrix();
            graphics.pose().translate(x, y);
            graphics.pose().scale(scale, scale);
            try { drawBoard(graphics); }
            finally { graphics.pose().popMatrix(); }
            super.render(graphics, pointer.mouseX(), pointer.mouseY(), partialTick);
        } finally { endScaledRender(graphics, pointer, partialTick); }
    }

    private void drawBoard(GuiGraphics graphics) {
        graphics.fill(0, 0, 320, 200, 0xff090d15);
        for (int star = 0; star < 35; star++) {
            int x = (star * 97 + 31) % 316 + 2, y = (star * 53 + 19) % 172 + 18;
            graphics.fill(x, y, x + 1, y + 1, 0xff353e50);
        }
        graphics.drawString(font, Component.translatable("lumi.astro.score", game.score()), 7, 5, LumiTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("lumi.astro.wave", game.wave()), 126, 5, LumiTheme.MUTED, false);
        graphics.drawString(font, "♥ ".repeat(game.lives()), 271, 5, LumiTheme.DANGER, false);
        for (var alien : game.invaders()) sprite(graphics, ALIEN, (int) alien.x() - 7,
                (int) alien.y() - 5, COLORS[alien.row()]);
        for (var shot : game.shots()) graphics.fill((int) shot.x(), (int) shot.y() - 2,
                (int) shot.x() + 2, (int) shot.y() + 3, shot.enemy() ? LumiTheme.DANGER : LumiTheme.accent());
        if (!game.immune() || System.currentTimeMillis() / 100 % 2 == 0) {
            sprite(graphics, SHIP, (int) game.shipX() - 7, LumiAstroInvadersGame.SHIP_Y - 5, LumiTheme.accent());
        }
        graphics.fill(7, 193, 313, 194, 0xff353e50);
        if (game.paused() || game.gameOver()) {
            graphics.fill(0, 20, 320, 190, 0xc0090d15);
            graphics.drawCenteredString(font, Component.translatable(game.gameOver()
                    ? "lumi.astro.game_over" : "lumi.astro.paused"), 160, 83, LumiTheme.TEXT);
            graphics.drawCenteredString(font, Component.translatable("lumi.astro.restart"), 160, 101, LumiTheme.MUTED);
        }
    }

    private static void sprite(GuiGraphics graphics, int[] rows, int x, int y, int color) {
        for (int row = 0; row < rows.length; row++) for (int column = 0; column < 7; column++) {
            if ((rows[row] & 1 << (6 - column)) != 0)
                graphics.fill(x + column * 2, y + row * 2, x + column * 2 + 2, y + row * 2 + 2, color);
        }
    }

    @Override public boolean keyPressed(KeyEvent event) {
        switch (event.key()) {
            case InputConstants.KEY_LEFT, InputConstants.KEY_A -> left = true;
            case InputConstants.KEY_RIGHT, InputConstants.KEY_D -> right = true;
            case InputConstants.KEY_SPACE -> firing = true;
            case InputConstants.KEY_P -> { if (!pauseHeld) game.togglePause(); pauseHeld = true; }
            case InputConstants.KEY_RETURN -> { if (game.gameOver()) game.restart(); }
            default -> { return super.keyPressed(event); }
        }
        return true;
    }

    @Override public boolean keyReleased(KeyEvent event) {
        switch (event.key()) {
            case InputConstants.KEY_LEFT, InputConstants.KEY_A -> left = false;
            case InputConstants.KEY_RIGHT, InputConstants.KEY_D -> right = false;
            case InputConstants.KEY_SPACE -> firing = false;
            case InputConstants.KEY_P -> pauseHeld = false;
            default -> { return super.keyReleased(event); }
        }
        return true;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
