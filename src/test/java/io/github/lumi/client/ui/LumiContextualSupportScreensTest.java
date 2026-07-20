package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiContextualSupportScreensTest {
    @Test
    void cleanupAndDiagnosticsExposeTheirV2HintsWithoutCoveringContent()
            throws Exception {
        String cleanup = source("LumiCleanupScreen.java");
        String diagnostics = source("LumiDiagnosticsScreen.java");

        assertTrue(cleanup.contains("ClientContextualHelpHint.CLEANUP"));
        assertTrue(cleanup.contains("contentOffset"));
        assertTrue(diagnostics.contains("ClientContextualHelpHint.DIAGNOSTICS"));
        assertTrue(diagnostics.contains("contentOffset"));
    }

    private static String source(String file) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui", file));
    }
}
