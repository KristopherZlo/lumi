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
        long idleMillis = 30 * 60 * 1_000L;
        assertFalse(dvd.advance(0, 40, 40, 160, 100, 48, 18, true));
        assertFalse(dvd.advance(idleMillis - 1, 40, 40, 160, 100, 48, 18, true));
        assertTrue(dvd.advance(idleMillis, 40, 40, 160, 100, 48, 18, true));
        assertEquals(0, dvd.opacity());
        assertTrue(dvd.advance(idleMillis + 300, 40, 40, 160, 100, 48, 18, true));
        assertEquals(.5F, dvd.opacity(), .001F);
        assertTrue(dvd.advance(idleMillis + 600, 40, 40, 160, 100, 48, 18, true));
        assertEquals(1, dvd.opacity());
        int color = dvd.color();
        boolean changed = false;
        boolean reachedEdge = false;
        for (long now = idleMillis + 700; now < idleMillis + 5_000; now += 100) {
            assertTrue(dvd.advance(now, 40, 40, 160, 100, 48, 18, true));
            assertTrue(dvd.x() >= 0 && dvd.x() <= 112);
            assertTrue(dvd.y() >= 0 && dvd.y() <= 82);
            reachedEdge |= dvd.x() == 112 || dvd.y() == 82 || dvd.x() == 0 || dvd.y() == 0;
            changed |= dvd.color() != color;
        }
        assertTrue(changed);
        assertTrue(reachedEdge);
        long dismissedAt = idleMillis + 5_000;
        assertFalse(dvd.allowInput(dismissedAt - 1));
        assertEquals(0, dvd.opacity());
        assertTrue(dvd.allowInput(dismissedAt));
        assertTrue(dvd.advance(dismissedAt + idleMillis, 40, 40, 160, 100, 48, 18, true));
        assertFalse(dvd.advance(dismissedAt + idleMillis + 1, 41, 40, 160, 100, 48, 18, true));
        dvd.activity(dismissedAt + idleMillis * 2);
        assertFalse(dvd.advance(dismissedAt + idleMillis * 2 + 1, 41, 40, 160, 100, 48, 18, true));
        assertFalse(dvd.advance(dismissedAt + idleMillis * 3, 41, 40, 160, 100, 48, 18, false));
    }
}
