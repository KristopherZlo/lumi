package io.github.luma.ui.overlay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompareOverlayHotkeyHudTest {

    @Test
    void hiddenOverlayDataDoesNotShowHotkeyPanel() {
        assertFalse(CompareOverlayHotkeyHud.shouldRenderForState(false, true, false, 8));
    }

    @Test
    void visibleChangedOverlayShowsHotkeyPanel() {
        assertTrue(CompareOverlayHotkeyHud.shouldRenderForState(false, true, true, 8));
    }

    @Test
    void emptyOrHiddenHudStateDoesNotReserveBottomSpace() {
        assertFalse(CompareOverlayHotkeyHud.reservedBottomHeightForState(false, true, false, 8) > 0);
        assertFalse(CompareOverlayHotkeyHud.reservedBottomHeightForState(true, true, true, 8) > 0);
    }
}
