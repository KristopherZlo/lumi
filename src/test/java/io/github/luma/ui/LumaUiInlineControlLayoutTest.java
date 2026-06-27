package io.github.luma.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LumaUiInlineControlLayoutTest {

    @Test
    void inlineOutlinedControlsKeepBottomBorderInsideTheirLayoutSlot() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/LumaUi.java"));

        Assertions.assertTrue(source.contains("private static final int CONTROL_HEIGHT = 18;"));
        Assertions.assertTrue(source.contains("private static final int SIDEBAR_TAB_BORDER_INSET = 2;"));
        Assertions.assertTrue(source.contains("UIContainers.horizontalFlow(Sizing.content(), Sizing.fixed(CONTROL_HEIGHT))"));
        Assertions.assertTrue(source.contains("control.margins(Insets.bottom(INLINE_WRAP_BOTTOM_MARGIN));"));
        Assertions.assertTrue(source.contains("component.getX() + SIDEBAR_TAB_BORDER_INSET"));
    }
}
