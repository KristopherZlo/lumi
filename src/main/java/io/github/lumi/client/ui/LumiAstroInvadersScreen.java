package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/** Pixel arcade launched only from the shortcut guide's secret code. */
final class LumiAstroInvadersScreen extends LumiModalScreen {
    private final Screen parent;
    private final LumiAstroInvadersSprites sprites = new LumiAstroInvadersSprites();
    private final LumiAstroInvadersGame game = new LumiAstroInvadersGame(this::playSound);
    private final LumiAstroInvadersStarfield starfield = new LumiAstroInvadersStarfield(new Random());
    private boolean left, right, firing, pauseHeld;
    private long lastFrame;
    private float animationTime;

    LumiAstroInvadersScreen(Screen parent) {
        super(parent, Component.translatable("lumi.astro.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        beginScreenInit();
        left = right = firing = pauseHeld = false;
        lastFrame = System.nanoTime();
    }

    @Override protected void renderUnderlay(GuiGraphics graphics) {
        // Leave the world visible outside the arcade window, without the parent modal's dimming.
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        if (!minecraft.isWindowActive()) left = right = firing = pauseHeld = false;
        else if (minecraft.screen == this) {
            float seconds = Math.clamp((now - lastFrame) / 1_000_000_000F, 0, .05F);
            if (!game.paused() && !game.gameOver() && !game.levelCleared()) animationTime += seconds;
            game.advance(seconds, left, right, firing);
        }
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
        drawSpace(graphics);
        graphics.drawString(font, Component.translatable("lumi.astro.score", game.score()), 7, 5, LumiTheme.TEXT, false);
        var level = Component.translatable("lumi.astro.level", game.level());
        graphics.drawString(font, level, 160 - font.width(level) / 2, 5, 0xffb5c7b0, false);
        sprites.drawLives(graphics, game.lives());
        for (var alien : game.invaders()) {
            sprites.drawInvader(graphics, alien, (int) (animationTime * 3) + alien.row());
        }
        for (var shot : game.shots()) {
            int x = (int) shot.x(), y = (int) shot.y();
            if (shot.enemy()) {
                graphics.fill(x, y - 3, x + 1, y + 3, 0xffe5afad);
                graphics.fill(x - 1, y - 1, x + 2, y + 1, 0xffe5afad);
            } else {
                graphics.fill(x, y - 3, x + 1, y + 2, 0xffd3ebc3);
            }
        }
        if (!game.immune() || System.currentTimeMillis() / 100 % 2 == 0) {
            sprites.drawShip(graphics, Math.round(game.shipX()), LumiAstroInvadersGame.SHIP_Y);
        }
        if (game.paused() || game.gameOver() || game.levelCleared()) {
            graphics.fill(0, 20, 320, 190, 0xc0090d15);
            String titleKey = game.gameOver() ? "lumi.astro.game_over"
                    : game.levelCleared() ? "lumi.astro.level_clear" : "lumi.astro.paused";
            String promptKey = game.gameOver() ? "lumi.astro.restart"
                    : game.levelCleared() ? "lumi.astro.next_level" : "lumi.astro.resume";
            graphics.drawCenteredString(font, Component.translatable(titleKey), 160, 83, LumiTheme.TEXT);
            graphics.drawCenteredString(font, Component.translatable(promptKey), 160, 101, LumiTheme.MUTED);
        }
    }

    private void drawSpace(GuiGraphics graphics) {
        graphics.fill(0, 0, 320, 200, 0xff101218);
        for (var star : starfield.stars()) {
            int x = star.x(), y = star.y();
            if (star.sparkle()) {
                graphics.fill(x - 1, y, x + 2, y + 1, 0xff353c4a);
                graphics.fill(x, y - 1, x + 1, y + 2, 0xff353c4a);
            }
            graphics.fill(x, y, x + 1, y + 1, star.color());
        }
    }

    private void playSound(LumiAstroInvadersGame.Event event) {
        var sound = switch (event) {
            case SHOT -> SoundEvents.NOTE_BLOCK_HAT;
            case HIT -> SoundEvents.NOTE_BLOCK_PLING;
            case DAMAGE -> SoundEvents.NOTE_BLOCK_BASEDRUM;
            case LEVEL_CLEAR -> SoundEvents.NOTE_BLOCK_CHIME;
            case GAME_OVER -> SoundEvents.NOTE_BLOCK_BASS;
        };
        float pitch = switch (event) {
            case SHOT, LEVEL_CLEAR -> 1.5F;
            case HIT -> 1.25F;
            case DAMAGE -> .75F;
            case GAME_OVER -> .5F;
        };
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound.value(), pitch,
                event == LumiAstroInvadersGame.Event.SHOT ? .08F : .16F));
    }

    @Override public boolean keyPressed(KeyEvent event) {
        switch (event.key()) {
            case InputConstants.KEY_LEFT, InputConstants.KEY_A -> left = true;
            case InputConstants.KEY_RIGHT, InputConstants.KEY_D -> right = true;
            case InputConstants.KEY_SPACE -> firing = true;
            case InputConstants.KEY_P -> { if (!pauseHeld) game.togglePause(); pauseHeld = true; }
            case InputConstants.KEY_RETURN -> {
                if (game.gameOver()) game.restart();
                else if (game.levelCleared()) game.nextLevel();
            }
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
