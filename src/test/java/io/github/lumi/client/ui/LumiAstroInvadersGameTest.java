package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import org.junit.jupiter.api.Test;

class LumiAstroInvadersGameTest {
    @Test
    void soundEventsFollowActualShotsAndLostLives() {
        var events = new ArrayList<LumiAstroInvadersGame.Event>();
        var game = new LumiAstroInvadersGame(events::add);
        game.advance(.05F, false, false, true);
        assertEquals(java.util.List.of(LumiAstroInvadersGame.Event.SHOT), events);
        for (int tick = 0; tick < 4; tick++) game.advance(.05F, false, false, true);
        assertEquals(1, events.size(), "Holding fire must respect the weapon cooldown");
        game.advance(.05F, false, false, true);
        assertEquals(2, events.size());
        game.restart();
        events.clear();
        int lostLives = 0;
        for (int tick = 0; tick < 10_000 && !game.gameOver(); tick++) {
            int lives = game.lives();
            game.advance(.05F, false, false, false);
            lostLives += lives - game.lives();
            assertEquals(lostLives, events.stream()
                    .filter(event -> event == LumiAstroInvadersGame.Event.DAMAGE).count());
        }
        assertTrue(game.gameOver());
        assertEquals(3, lostLives);
        assertEquals(1, events.stream().filter(event -> event == LumiAstroInvadersGame.Event.GAME_OVER).count());
    }

    @Test
    void levelsContinueBeyondFiveAndRequireConfirmation() {
        var events = new ArrayList<LumiAstroInvadersGame.Event>();
        var game = new LumiAstroInvadersGame(events::add);
        game.advance(60, false, true, true);
        assertEquals(java.util.List.of(LumiAstroInvadersGame.Event.SHOT), events);
        events.clear();
        assertEquals(1, game.shots().size());
        assertEquals(166.5F, game.shipX(), .01F);
        game.restart();
        float previousStep = 0;
        for (int level = 1; level <= 7; level++) {
            assertEquals(level, game.level());
            game.nextLevel();
            assertEquals(level, game.level(), "An unfinished level cannot be skipped");
            float x = game.invaders().getFirst().x();
            game.advance(.05F, false, false, false);
            float step = game.invaders().getFirst().x() - x;
            assertTrue(step > previousStep, "Each level moves faster than the previous one");
            previousStep = step;
            playUntilCleared(game);
            assertTrue(game.levelCleared(), "Level " + level + " must be completable");
            assertTrue(game.shots().isEmpty());
            int emitted = events.size(), score = game.score(), lives = game.lives();
            game.advance(10, false, true, true);
            assertEquals(emitted, events.size(), "A cleared level emits its cue once");
            assertEquals(level, game.level(), "A cleared level waits for confirmation");
            game.nextLevel();
            assertEquals(score, game.score());
            assertEquals(Math.min(3, lives + 1), game.lives());
            assertEquals(level + 1, game.level());
        }
        assertTrue(game.score() > 0, "Player shots must award points for destroyed invaders");
        assertEquals(8, game.level());
        assertEquals(7, events.stream().filter(event -> event == LumiAstroInvadersGame.Event.LEVEL_CLEAR).count());
        assertTrue(events.contains(LumiAstroInvadersGame.Event.HIT));
    }

    @Test
    void pauseStopsSimulationAndRestartClearsThePreviousGame() {
        var events = new ArrayList<LumiAstroInvadersGame.Event>();
        var game = new LumiAstroInvadersGame(events::add);
        for (int tick = 0; tick < 30; tick++) game.advance(.05F, false, true, true);
        assertFalse(game.shots().isEmpty());
        float ship = game.shipX(), alien = game.invaders().getFirst().x();
        int shots = game.shots().size();
        game.togglePause();
        int emitted = events.size();
        game.advance(.05F, true, false, true);
        assertEquals(ship, game.shipX());
        assertEquals(alien, game.invaders().getFirst().x());
        assertEquals(shots, game.shots().size());
        assertEquals(emitted, events.size(), "A paused game emits no sound events");
        game.restart();
        assertFalse(game.paused());
        assertEquals(160, game.shipX());
        assertEquals(3, game.lives());
        assertEquals(1, game.level());
        assertEquals(0, game.score());
        assertEquals(8, game.invaders().size());
        assertTrue(game.shots().isEmpty());
        events.clear();
        for (int tick = 0; tick < 10_000 && !game.gameOver(); tick++) {
            game.advance(.05F, false, false, false);
        }
        assertTrue(game.gameOver());
        assertEquals(1, events.stream().filter(event -> event == LumiAstroInvadersGame.Event.GAME_OVER).count());
        emitted = events.size();
        game.advance(.05F, false, false, true);
        assertEquals(emitted, events.size(), "Game over emits its cue once");
    }

    private static void playUntilCleared(LumiAstroInvadersGame game) {
        LumiAstroInvadersGame.Invader tracked = null;
        float previousX = 0;
        var previousShots = new IdentityHashMap<LumiAstroInvadersGame.Shot, float[]>();
        for (int tick = 0; tick < 20_000 && !game.levelCleared() && !game.gameOver(); tick++) {
            var alien = game.invaders().getFirst();
            float speed = alien == tracked ? (alien.x() - previousX) / .05F : 0;
            tracked = alien;
            previousX = alien.x();
            float target = Math.clamp(alien.x() + speed * (180 - alien.y()) / 180F, 9, 311);
            for (var shot : game.shots()) {
                float[] previous = previousShots.put(shot, new float[]{shot.x(), shot.y()});
                if (!shot.enemy() || previous == null || shot.y() <= previous[1]) continue;
                float time = (180 - shot.y()) / ((shot.y() - previous[1]) / .05F);
                float impact = shot.x() + (shot.x() - previous[0]) / .05F * time;
                if (time > 0 && time < .8F && (Math.abs(impact - game.shipX()) < 26 || Math.abs(impact - target) < 20)) {
                    target = impact < game.shipX() ? Math.min(311, impact + 40) : Math.max(9, impact - 40);
                }
            }
            previousShots.keySet().retainAll(game.shots());
            game.advance(.05F, game.shipX() > target + 2, game.shipX() < target - 2, true);
            assertTrue(game.shipX() >= 9 && game.shipX() <= 311);
            assertTrue(game.shots().size() <= LumiAstroInvadersGame.MAX_SHOTS);
            assertTrue(game.shots().stream().allMatch(shot -> shot.x() >= 0 && shot.x() <= 320
                    && shot.y() >= 0 && shot.y() <= 200));
            assertTrue(game.invaders().size() <= 24);
            assertTrue(game.lives() >= 0 && game.lives() <= 3);
        }
    }
}
