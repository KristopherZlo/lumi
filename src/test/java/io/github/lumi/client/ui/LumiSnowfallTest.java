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

    @Test
    void screensaverWaitsForIdleBouncesAndDismissesOnInput() {
        var dvd = new LumiDvdScreensaver();
        assertFalse(dvd.advance(0, 40, 40, 160, 100, 48, 18, true));
        assertFalse(dvd.advance(4_999, 40, 40, 160, 100, 48, 18, true));
        assertTrue(dvd.advance(5_000, 40, 40, 160, 100, 48, 18, true));
        int color = dvd.color();
        boolean changed = false;
        for (long now = 5_100; now < 10_000; now += 100) {
            assertTrue(dvd.advance(now, 40, 40, 160, 100, 48, 18, true));
            assertTrue(dvd.x() >= 8 && dvd.x() <= 104);
            assertTrue(dvd.y() >= 8 && dvd.y() <= 74);
            changed |= dvd.color() != color;
        }
        assertTrue(changed);
        assertFalse(dvd.allowInput(9_999));
        assertTrue(dvd.allowInput(10_000));
        assertTrue(dvd.advance(15_000, 40, 40, 160, 100, 48, 18, true));
        assertFalse(dvd.advance(15_001, 41, 40, 160, 100, 48, 18, true));
        dvd.activity(20_000);
        assertFalse(dvd.advance(20_001, 41, 40, 160, 100, 48, 18, true));
        assertFalse(dvd.advance(30_000, 41, 40, 160, 100, 48, 18, false));
    }
}
