package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class LumiButtonTest {
    @Test
    void matchesV2FlatButtonRenderingWithoutOwo() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiButton.java"));

        assertTrue(source.contains("font.plainSubstrByWidth"));
        assertTrue(source.contains("x, y, width,"));
        assertTrue(source.contains("getWidth() > ICON_BUTTON_WIDTH"));
        assertTrue(source.contains("icon == null ? 4 : 20"));
        assertTrue(source.contains("graphics.enableScissor"));
        assertTrue(source.contains("private static final int CONTROL_HEIGHT = 18;"));
        assertTrue(source.contains("private static final int ICON_BUTTON_WIDTH = 26;"));
        assertTrue(source.contains("Integer accentColor"));
        assertTrue(source.contains("LumiTheme.withAlpha"));
        assertTrue(source.contains("\"textures/gui/icons/\" + iconName + \".png\""));
        assertTrue(source.contains("\"textures/gui/icons/\" + iconName + \"_disabled.png\""));
        assertTrue(source.contains("active ? icon : disabledIcon"));
        assertFalse(source.contains("new-icons/sliders.png"));
        assertTrue(Files.exists(Path.of(
                "src/main/resources/assets/lumi/textures/gui/icons/sliders.png")));
        assertFalse(source.contains("kind.border()"));
        assertTrue(source.contains("Tooltip.create"));
        assertFalse(source.toLowerCase(Locale.ROOT).contains("owo"));
    }

    @Test
    void contentWidthIsBoundedWithoutChangingFixedGridSlots() {
        assertEquals(18, LumiButton.fittedWidth(100, 0));
        assertEquals(42, LumiButton.fittedWidth(100, 30));
        assertEquals(32, LumiButton.fittedWidth(32, 100));
        assertEquals(0, LumiButton.fittedWidth(0, 100));
    }
}
