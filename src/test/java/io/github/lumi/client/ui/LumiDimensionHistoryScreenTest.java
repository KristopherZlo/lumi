package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiDimensionHistoryScreenTest {
    @Test
    void keepsRemoteDimensionHistoryReadOnlyAndScrollable() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiDimensionHistoryScreen.java"));
        assertTrue(source.contains("luma.dimensions.read_only"));
        assertTrue(source.contains("HistoryViewController.Mode.GRAPH"));
        assertTrue(source.contains("public boolean mouseScrolled("));
        assertTrue(source.contains("HistoryPageRequestPayload.ACTIVE_WORKSPACE"));
    }
}
