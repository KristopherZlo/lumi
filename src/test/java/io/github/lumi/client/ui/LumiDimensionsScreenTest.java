package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiDimensionsScreenTest {
    @Test
    void listsRegistryDimensionsAndOpensTheirHistory() throws Exception {
        String screen = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiDimensionsScreen.java"));
        String client = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClient.java"));
        assertTrue(screen.contains("openHistory.accept(dimension)"));
        assertTrue(screen.contains("public boolean mouseScrolled("));
        assertTrue(client.contains("getConnection().levels()"));
        assertTrue(client.contains("requestDimensionHistoryPage"));
    }
}
