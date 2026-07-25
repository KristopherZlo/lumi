package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LumiSnowfallTest {
    @Test
    void flakesFinishFallingAfterEmissionStops() {
        LumiSnowfall snow = new LumiSnowfall();
        snow.advance(true, 100, 40, 0);
        int emitted = snow.advance(true, 100, 40, 100);

        assertTrue(emitted > 0);
        int remaining = emitted;
        for (long now = 200; now <= 5_000; now += 100) {
            remaining = snow.advance(false, 100, 40, now);
        }
        assertEquals(0, remaining);
    }

    @Test
    void upsideDownNamesRequireAnExactCaseInsensitiveSearch() {
        assertTrue(LumiDashboardScreen.upsideDownSearch("Dinnerbone"));
        assertTrue(LumiDashboardScreen.upsideDownSearch("Grumm"));
        assertTrue(LumiDashboardScreen.upsideDownSearch("dinnerbone"));
        assertFalse(LumiDashboardScreen.upsideDownSearch("Dinnerbone "));
        assertFalse(LumiDashboardScreen.upsideDownSearch("dinnerbon"));
    }
}
