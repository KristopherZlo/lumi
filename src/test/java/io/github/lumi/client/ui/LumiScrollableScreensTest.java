package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LumiScrollableScreensTest {
    @Test
    void everyScreenWithWheelScrollingRendersItsScrollbar() throws Exception {
        Path ui = Path.of("src/main/java/io/github/lumi/client/ui");
        try (Stream<Path> files = Files.list(ui)) {
            for (Path file : files.filter(path ->
                    path.getFileName().toString().endsWith("Screen.java")).toList()) {
                String source = Files.readString(file);
                if (source.contains("public boolean mouseScrolled(")
                        && !file.getFileName().toString()
                                .equals("LumiLegacyModalScreen.java")) {
                    assertTrue(source.contains("renderLegacyScrollbar("),
                            () -> file.getFileName() + " hides its scroll position");
                }
            }
        }
    }
}
