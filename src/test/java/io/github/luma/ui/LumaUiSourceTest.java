package io.github.luma.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LumaUiSourceTest {

    @Test
    void zoneSidebarTabsTintFillAndSelectedFill() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/LumaUi.java"));
        int methodIndex = source.indexOf("    public static ButtonComponent sidebarTab(Component text, boolean selected, int innerBorder, Consumer<ButtonComponent> onPress) {");
        int nextMethodIndex = source.indexOf("    public static FlowLayout revealGroup() {", methodIndex);

        assertTrue(methodIndex >= 0, "Zone-colored sidebarTab overload should exist");
        assertTrue(nextMethodIndex > methodIndex, "Zone-colored sidebarTab overload should be bounded by revealGroup");

        String methodBody = source.substring(methodIndex, nextMethodIndex);

        assertTrue(methodBody.contains("withAlpha(innerBorder"),
                "Zone-colored sidebar tabs should tint their fill from the zone color");
        assertTrue(methodBody.contains("selectedFill"),
                "Zone-colored sidebar tabs should use a zone-tinted selected fill");
    }
}
