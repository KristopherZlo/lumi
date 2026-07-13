package io.github.luma.minecraft.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldBootstrapDelayTest {

    @Test
    void waitsForQuietChunkLoadingAfterMinimumDelay() {
        WorldBootstrapDelay delay = new WorldBootstrapDelay();

        for (int tick = 0; tick < 199; tick++) {
            assertFalse(delay.tick(false));
        }
        assertFalse(delay.tick(true));
        for (int tick = 0; tick < 99; tick++) {
            assertFalse(delay.tick(false));
        }

        assertTrue(delay.tick(false));
    }

    @Test
    void forcesBootstrapAfterMaximumDelay() {
        WorldBootstrapDelay delay = new WorldBootstrapDelay();

        for (int tick = 0; tick < 599; tick++) {
            assertFalse(delay.tick(true));
        }

        assertTrue(delay.tick(true));
    }
}
