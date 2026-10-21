package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LumiAstroInvadersStarfieldTest {
    @Test
    void keepsStarsInsideTheBoardBelowTheHudWithoutCrowdedOrEmptyCells() {
        for (int seed = 0; seed < 100; seed++) {
            var field = new LumiAstroInvadersStarfield(new Random(seed));
            var occupied = new HashSet<Integer>();
            assertEquals(40, field.stars().size());
            for (var star : field.stars()) {
                assertTrue(star.x() >= 11 && star.x() <= 308);
                assertTrue(star.y() >= 27 && star.y() <= 180);
                assertTrue(occupied.add((star.y() - 24) / 32 * 8 + (star.x() - 8) / 38));
            }
            assertEquals(2, field.stars().stream().filter(LumiAstroInvadersStarfield.Star::sparkle).count());
            assertEquals(30, field.stars().stream().filter(star -> star.color() == 0xff353c4a).count());
        }
    }

    @Test
    void holdsTheSameLayoutForItsLifetimeAndChangesWithTheRandomSeed() {
        var field = new LumiAstroInvadersStarfield(new Random(12));
        assertSame(field.stars(), field.stars());
        assertEquals(field.stars(), new LumiAstroInvadersStarfield(new Random(12)).stars());
        assertNotEquals(field.stars(), new LumiAstroInvadersStarfield(new Random(13)).stars());
        assertThrows(UnsupportedOperationException.class, () -> field.stars().clear());
    }
}
