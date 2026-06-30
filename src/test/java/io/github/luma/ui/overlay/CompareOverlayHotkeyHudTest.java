package io.github.luma.ui.overlay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void hotkeyPanelUsesDarkButtonSpritesWithPressedState() throws IOException {
        String hud = Files.readString(Path.of("src/client/java/io/github/luma/ui/overlay/CompareOverlayHotkeyHud.java"));
        String renderer = Files.readString(Path.of("src/client/java/io/github/luma/ui/overlay/RoundedHudRenderer.java"));

        assertTrue(hud.contains("RoundedHudRenderer.key("));
        assertTrue(hud.contains("key != null && key.isDown()"));
        assertFalse(renderer.contains("if (compact) {\n            return textChip"));
        assertTrue(renderer.contains("KeyGlyphResolver.resolve(key)"));
    }
}
