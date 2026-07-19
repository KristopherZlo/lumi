package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiMoreScreenTest {
    @Test
    void retainsLegacyCleanupAndManualCompareRoutes() throws Exception {
        String more = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiMoreScreen.java"));
        String client = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClient.java"));
        String cleanup = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiCleanupScreen.java"));

        assertTrue(more.contains("luma.action.open_cleanup"));
        assertTrue(more.contains("luma.action.dimensions"));
        assertTrue(more.contains("luma.action.manual_compare"));
        assertTrue(more.contains("ClientContextualHelpHint.MORE"));
        assertTrue(more.contains("luma.action.reset_contextual_hints"));
        assertTrue(more.contains("resetContextualHints"));
        assertTrue(more.contains("actionX += width + 4"));
        assertFalse(more.contains("luma.action.settings"));
        assertFalse(more.contains("luma.action.buy_me_a_coffee"));
        assertFalse(more.contains("luma.action.paypal_donate"));
        assertTrue(client.contains("new LumiCleanupScreen("));
        assertTrue(client.contains("new LumiComparePickerScreen("));
        assertTrue(cleanup.contains("luma.action.inspect_unused_files"));
        assertTrue(cleanup.contains("luma.action.clean_up"));
        assertTrue(cleanup.contains("!result.applied()"));
        assertTrue(cleanup.contains("response.requestId().equals(pendingRequest)"));
    }
}
