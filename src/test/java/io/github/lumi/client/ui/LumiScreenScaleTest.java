package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LumiScreenScaleTest {
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
                                    && (source.contains("extends LumiModalScreen")
                                    || source.contains("extends LumiPageScreen"));
                        } catch (java.io.IOException failed) {
                            throw new java.io.UncheckedIOException(failed);
                        }
                    }).toList();
        }

        assertFalse(screens.isEmpty());
        for (Path screen : screens) {
            String source = Files.readString(screen);
            assertTrue(source.contains("beginScreenInit();"), screen.toString());
            assertTrue(source.matches(
                            "(?s).*beginScaledRender\\(\\s*graphics,\\s*mouseX,\\s*mouseY\\).*"),
                    screen.toString());
            assertTrue(source.contains("endScaledRender(graphics);"), screen.toString());
        }
    }
}
