package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(more.contains("new MoreAction("));
        assertTrue(more.contains("public boolean mouseScrolled("));
        assertTrue(more.contains("renderedY + 18 > actionBottom"));
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

    @Test
    void wrappedToolsScrollByWholeRows() {
        assertEquals(0, LumiMoreScreen.requiredScrollRows(100, 100));
        assertEquals(1, LumiMoreScreen.requiredScrollRows(101, 100));
        assertEquals(2, LumiMoreScreen.requiredScrollRows(125, 100));
        assertFalse(LumiMoreScreen.supportsContextualHint(160));
        assertFalse(LumiMoreScreen.supportsContextualHint(180));
        assertTrue(LumiMoreScreen.supportsContextualHint(220));
    }
}
