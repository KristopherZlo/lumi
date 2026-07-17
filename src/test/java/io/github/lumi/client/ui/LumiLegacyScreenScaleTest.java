package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LumiLegacyScreenScaleTest {
    @Test
    void everyNativeScreenUsesScaledLayoutRenderAndInputCoordinates() throws Exception {
        List<Path> screens;
        try (var files = Files.list(Path.of(
                "src/main/java/io/github/lumi/client/ui"))) {
            screens = files.filter(path -> path.getFileName().toString().endsWith("Screen.java"))
                    .filter(path -> {
                        try {
                            String source = Files.readString(path);
                            return source.contains("public final class")
                                    && (source.contains("extends LumiLegacyModalScreen")
                                    || source.contains("extends LumiLegacyPageScreen"));
                        } catch (java.io.IOException failed) {
                            throw new java.io.UncheckedIOException(failed);
                        }
                    }).toList();
        }

        assertEquals(23, screens.size());
        for (Path screen : screens) {
            String source = Files.readString(screen);
            assertTrue(source.contains("beginLegacyInit();"), screen.toString());
            assertTrue(source.contains("beginLegacyRender(graphics, mouseX, mouseY)"),
                    screen.toString());
            assertTrue(source.contains("endLegacyRender(graphics);"), screen.toString());
        }
    }
}
