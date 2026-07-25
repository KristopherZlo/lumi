package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.VersionTags;
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
    void keepsFreshFlakesWhileHoverRemainsActive() {
        LumiSnowfall snow = new LumiSnowfall();
        snow.advance(true, 100, 360, 0);
        for (long now = 100; now <= 6_000; now += 100) {
            snow.advance(true, 100, 360, now);
        }

        int remaining = 0;
        for (long now = 6_100; now <= 23_000; now += 100) {
            remaining = snow.advance(false, 100, 360, now);
        }
        assertTrue(remaining > 0);
    }

    @Test
    void upsideDownCardsAreSelectedByExactNormalizedTags() {
        assertTrue(LumiDashboardScreen.upsideDownTag(
                VersionTags.parse("Dinnerbone")));
        assertTrue(LumiDashboardScreen.upsideDownTag(
                VersionTags.parse("roof, Grumm")));
        assertFalse(LumiDashboardScreen.upsideDownTag(
                VersionTags.parse("dinnerbon")));
    }
}
