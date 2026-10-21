package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.mojang.blaze3d.platform.InputConstants;

import org.junit.jupiter.api.Test;

class LumiHotkeyScreenTest {
    @Test
    void fitsAndScrollsShortcutRowsAtSupportedViewports() {
        assertGeometry(360, 336, 7);
        assertGeometry(240, 216, 4);
        assertGeometry(200, 176, 2);
        assertGeometry(180, 156, 2);
    }

    private static void assertGeometry(
            int screenHeight, int expectedHeight, int expectedRows) {
        int panelHeight = LumiHotkeyScreen.fittedPanelHeight(screenHeight, 8);
        assertEquals(expectedHeight, panelHeight);
        assertEquals(expectedRows,
                LumiHotkeyScreen.visibleShortcutRows(panelHeight, 8));
        assertTrue(53 + expectedRows * 34 <= panelHeight - 23);
    }

    @Test
    void secretCodeRequiresTheWholeSequenceAndRecoversAfterOtherKeys() {
        var code = new LumiHotkeyScreen.SecretCode();
        int[] sequence = {InputConstants.KEY_UP, InputConstants.KEY_UP,
                InputConstants.KEY_DOWN, InputConstants.KEY_DOWN, InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT,
                InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT, InputConstants.KEY_B, InputConstants.KEY_A};
        assertFalse(code.press(InputConstants.KEY_A));
        assertFalse(code.press(InputConstants.KEY_UP));
        assertEquals(1, code.progress());
        assertFalse(code.press(InputConstants.KEY_UP));
        assertEquals(2, code.progress());
        assertFalse(code.press(InputConstants.KEY_UP));
        assertEquals(0, code.progress(), "A wrong key clears all highlighted symbols");
        for (int index = 0; index < sequence.length - 1; index++) {
            assertFalse(code.press(sequence[index]));
            assertEquals(index + 1, code.progress());
        }
        assertTrue(code.press(sequence[sequence.length - 1]));
        assertEquals(0, code.progress());
        assertFalse(code.press(InputConstants.KEY_A));
    }
}
