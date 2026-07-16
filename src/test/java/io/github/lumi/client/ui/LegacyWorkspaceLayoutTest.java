package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LegacyWorkspaceLayoutTest {
    @Test
    void retainsWideAndNarrowLegacySidebarGeometry() {
        LegacyWorkspaceLayout wide = LegacyWorkspaceLayout.fit(1280, 720);
        assertEquals(10, wide.windowX());
        assertEquals(172, wide.sidebarWidth());
        assertEquals(182, wide.contentX());
        assertEquals(1060, wide.bodyWidth());

        LegacyWorkspaceLayout narrow = LegacyWorkspaceLayout.fit(640, 360);
        assertEquals(136, narrow.sidebarWidth());
        assertEquals(146, narrow.contentX());
        assertEquals(456, narrow.bodyWidth());
    }
}
