package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LumiAstroInvadersGameTest {
    @Test
    void boundsMovementProjectilesAndLongFrames() {
        var game = new LumiAstroInvadersGame();
        game.advance(60, false, true, true);
        assertEquals(166.5F, game.shipX(), .01F);
        for (int tick = 0; tick < 2_000 && !game.gameOver(); tick++) {
            game.advance(.05F, tick % 200 < 100, tick % 200 >= 100, true);
            assertTrue(game.shipX() >= 9 && game.shipX() <= 311);
            assertTrue(game.shots().size() <= LumiAstroInvadersGame.MAX_SHOTS);
            assertTrue(game.invaders().size() <= 24);
            assertTrue(game.lives() >= 0 && game.lives() <= 3);
        }
        assertTrue(game.score() > 0, "Player projectiles must hit invaders");
    }

    @Test
    void pauseStopsSimulationAndRestartClearsThePreviousGame() {
        var game = new LumiAstroInvadersGame();
        for (int tick = 0; tick < 30; tick++) game.advance(.05F, false, true, true);
        assertFalse(game.shots().isEmpty());
        float ship = game.shipX(), alien = game.invaders().getFirst().x();
        int shots = game.shots().size();
        game.togglePause();
        game.advance(.05F, true, false, true);
        assertEquals(ship, game.shipX());
        assertEquals(alien, game.invaders().getFirst().x());
        assertEquals(shots, game.shots().size());
        game.restart();
        assertFalse(game.paused());
        assertEquals(160, game.shipX());
        assertEquals(3, game.lives());
        assertEquals(1, game.wave());
        assertEquals(0, game.score());
        assertEquals(24, game.invaders().size());
        assertTrue(game.shots().isEmpty());
    }
}
