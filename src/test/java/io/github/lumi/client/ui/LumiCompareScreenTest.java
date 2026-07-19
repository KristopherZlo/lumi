package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiCompareScreenTest {
    @Test
    void keepsCompletedWorldHighlightReachableAfterClosingTheSummary() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiCompareScreen.java"));

        assertTrue(source.contains("luma.action.hide_highlight"));
        assertTrue(source.contains("luma.action.show_highlight"));
        assertTrue(source.contains("comparisons.toggleVisibility()"));
        assertTrue(source.contains("comparisons.result().isEmpty()"));
    }
}
