package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LumiCleanupScreenTest {
    @Test
    void keepsHintScrollableResultAndFooterInsideSupportedViewports() {
        int hintHeight = 70;
        for (int[] viewport : new int[][] {{320, 180}, {427, 240}, {640, 360}}) {
            LegacyModalLayout panel = LumiCleanupScreen.fitPanel(
                    viewport[0], viewport[1], hintHeight + 8);
            var geometry = LumiCleanupScreen.cleanupGeometry(
                    panel.height(), hintHeight);

            assertTrue(panel.x() >= 0 && panel.y() >= 0);
            assertTrue(panel.x() + panel.width() <= viewport[0]);
            assertTrue(panel.y() + panel.height() <= viewport[1]);
            assertTrue(geometry.hintY() >= 0);
            assertTrue(geometry.hintY() + geometry.hintHeight()
                    <= geometry.resultY());
            assertTrue(geometry.resultY() + geometry.resultHeight()
                    <= geometry.actionY() - 6);
            assertTrue(geometry.actionY() + 18 <= panel.height());
            assertTrue(LumiCleanupScreen.visibleResultLines(
                    geometry.resultHeight()) >= 1);
        }
    }
}
