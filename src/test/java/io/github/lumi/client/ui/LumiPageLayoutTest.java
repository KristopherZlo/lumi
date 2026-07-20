package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LumiPageLayoutTest {
    @Test
    void retainsWideAndNarrowV2SidebarGeometry() {
        LumiPageLayout wide = LumiPageLayout.fit(1280, 720);
        assertEquals(10, wide.windowX());
        assertEquals(172, wide.sidebarWidth());
        assertEquals(182, wide.contentX());
        assertEquals(1076, wide.bodyWidth());

        LumiPageLayout narrow = LumiPageLayout.fit(640, 360);
        assertEquals(136, narrow.sidebarWidth());
        assertEquals(146, narrow.contentX());
        assertEquals(472, narrow.bodyWidth());
    }

    @Test
    void neverExceedsSmallOrLargeViewports() {
        LumiPageLayout small = LumiPageLayout.fit(320, 200);
        LumiPageLayout large = LumiPageLayout.fit(1920, 1080);

        assertTrue(small.windowX() + small.windowWidth() <= 320);
        assertTrue(small.windowY() + small.windowHeight() <= 200);
        assertEquals(136, small.sidebarWidth());
        assertEquals(1900, large.windowWidth());
        assertEquals(1060, large.windowHeight());
    }
}
