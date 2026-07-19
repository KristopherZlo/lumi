package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LumiHotkeyScreenTest {
    @Test
    void fitsAndScrollsShortcutRowsAtSupportedViewports() {
        assertGeometry(360, 336, 8);
        assertGeometry(240, 216, 4);
        assertGeometry(200, 176, 3);
        assertGeometry(180, 156, 3);
    }

    private static void assertGeometry(
            int screenHeight, int expectedHeight, int expectedRows) {
        int panelHeight = LumiHotkeyScreen.fittedPanelHeight(screenHeight, 8);
        assertEquals(expectedHeight, panelHeight);
        assertEquals(expectedRows,
                LumiHotkeyScreen.visibleShortcutRows(panelHeight, 8));
    }
}
