package io.github.luma.ui.screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LumaScreenScaleTest {

    @Test
    void buildUsesVirtualDimensionsBeforeOwoInflatesLayout() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/LumaScreen.java"));

        int widthIndex = source.indexOf("this.width = LumaUiScale.virtualSize(this.width, currentGuiScale);");
        int heightIndex = source.indexOf("this.height = LumaUiScale.virtualSize(this.height, currentGuiScale);");
        int superIndex = source.indexOf("super.init();");

        Assertions.assertTrue(widthIndex >= 0);
        Assertions.assertTrue(heightIndex > widthIndex);
        Assertions.assertTrue(superIndex > heightIndex);
        Assertions.assertFalse(source.contains("resizeLumaUi()"));
    }
}
