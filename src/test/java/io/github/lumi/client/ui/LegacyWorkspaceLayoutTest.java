package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LegacyWorkspaceLayoutTest {
    @Test
    void retainsWideAndNarrowLegacySidebarGeometry() {
        LegacyWorkspaceLayout wide = LegacyWorkspaceLayout.fit(1280, 720);
        assertEquals(160, wide.windowX());
        assertEquals(172, wide.sidebarWidth());
        assertEquals(332, wide.contentX());
        assertEquals(760, wide.bodyWidth());

        LegacyWorkspaceLayout narrow = LegacyWorkspaceLayout.fit(640, 360);
        assertEquals(136, narrow.sidebarWidth());
        assertEquals(146, narrow.contentX());
        assertEquals(456, narrow.bodyWidth());
    }

    @Test
    void neverExceedsSmallOrLargeViewports() {
        LegacyWorkspaceLayout small = LegacyWorkspaceLayout.fit(320, 200);
        LegacyWorkspaceLayout large = LegacyWorkspaceLayout.fit(1920, 1080);

        assertTrue(small.windowX() + small.windowWidth() <= 320);
        assertTrue(small.windowY() + small.windowHeight() <= 200);
        assertEquals(112, small.sidebarWidth());
        assertEquals(960, large.windowWidth());
        assertEquals(540, large.windowHeight());
    }
}
