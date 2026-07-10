package io.github.luma.ui.screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LumaScreenScaleTest {

    @Test
    void buildUsesVirtualDimensionsBeforeOwoInflatesLayout() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/LumaScreen.java"))
                .replace("\r\n", "\n");

        int widthIndex = source.indexOf("this.width = LumaUiScale.virtualSize(this.width, currentGuiScale);");
        int heightIndex = source.indexOf("this.height = LumaUiScale.virtualSize(this.height, currentGuiScale);");
        int superIndex = source.indexOf("super.init();");

        Assertions.assertTrue(widthIndex >= 0);
        Assertions.assertTrue(heightIndex > widthIndex);
        Assertions.assertTrue(superIndex > heightIndex);
        Assertions.assertFalse(source.contains("resizeLumaUi()"));
    }

    @Test
    void tooltipsUseLumaScaleAfterVirtualHoverLookup() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/LumaScreen.java"))
                .replace("\r\n", "\n");

        int virtualHoverIndex = source.indexOf("childAt(virtualMouseX, virtualMouseY)");
        int drawIndex = source.indexOf("graphics.renderTooltip(");
        int scaleIndex = source.indexOf("graphics.pose().scaleAround(this.lumaUiScale(), mouseX, mouseY);", virtualHoverIndex);

        Assertions.assertTrue(virtualHoverIndex >= 0);
        Assertions.assertTrue(scaleIndex > virtualHoverIndex);
        Assertions.assertTrue(drawIndex > scaleIndex);
        Assertions.assertTrue(source.contains("hovered.shouldDrawTooltip(virtualMouseX, virtualMouseY)"));
        Assertions.assertTrue(source.contains("mouseX,\n                            mouseY,"));
        Assertions.assertFalse(source.contains("super.drawComponentTooltip(graphics, this.virtualCoordinate(mouseX), this.virtualCoordinate(mouseY), partialTick);"));
    }

    @Test
    void labelsUseLumaScaleBaselineOffset() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/LumaUi.java"));
        String label = Files.readString(Path.of("src/client/java/io/github/luma/ui/LumaLabelComponent.java"));

        Assertions.assertTrue(source.contains("new LumaLabelComponent(text)"));
        Assertions.assertFalse(source.contains("UIComponents.label("));
        Assertions.assertTrue(label.contains("LumaUiScale.targetPixelOffset()"));
        Assertions.assertFalse(label.contains("getGuiScale()"));
    }
}
