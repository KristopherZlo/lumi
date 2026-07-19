package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiDashboardContextualHelpTest {
    @Test
    void retainsHintPriorityAndReservesItsDashboardBand()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiDashboardScreen.java"));

        assertTrue(source.contains("ClientContextualHelpHint.HISTORY"));
        assertTrue(source.contains("ClientContextualHelpHint.SHORTCUTS"));
        assertTrue(source.contains("ClientContextualHelpHint.CLEAN_STATE"));
        assertTrue(source.contains("ClientContextualHelpHint.SAVE"));
        assertTrue(source.contains("ClientContextualHelpHint.QUICK_ROLLBACK"));
        assertTrue(source.contains("addDashboardHint"));
        assertTrue(source.contains("contextualHintOffset(0)"));
        assertTrue(source.contains("dashboardGeometry = dashboardGeometry("));
    }
}
