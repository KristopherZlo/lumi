package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiContextualHintNavigationTest {
    @Test
    void usesStablePreviousNextAndCloseControls() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiScreen.java"));

        assertTrue(source.contains("\"chevron-left\""));
        assertTrue(source.contains("last ? \"close\" : \"chevron-right\""));
        assertTrue(source.contains("hintPreviousButton.active = contextualHintIndex > 0"));
        assertTrue(source.contains("+ \"/\" + contextualHints.size()"));
        assertTrue(source.contains(".max().orElse(1) * 10"));
        assertTrue(source.contains("MAX_HINTS_PER_GROUP = 3"));
        assertTrue(source.contains("!visible.equals(previousContextualHints)"));
        assertTrue(source.contains("if (contextualHints.size() > 1)"));
        assertTrue(source.contains("if (contextualHints.size() == 1)"));
    }
}
