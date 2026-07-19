package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiDeletedVersionsScreenTest {
    @Test
    void exposesRestoreCleanupAndScrollInsideTheVisibleHeight() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiDeletedVersionsScreen.java"));

        assertTrue(source.contains("luma.action.restore_deleted_save"));
        assertTrue(source.contains("restore.accept(version.id())"));
        assertTrue(source.contains("pendingCleanup"));
        assertTrue(source.contains("panelY + footerOffset(panelHeight)"));
        assertTrue(source.contains("visibleRows()"));
        assertTrue(source.contains("mouseScrolled("));
    }

    @Test
    void compactConfirmationKeepsEveryLineAboveItsFooter() {
        assertConfirmationGeometry(160, 56, 64, 80, 96, 112, 132);
        assertConfirmationGeometry(180, 66, 82, 100, 116, 132, 152);
        assertConfirmationGeometry(220, 66, 82, 108, 140, 156, 192);
        assertConfirmationGeometry(340, 66, 82, 108, 140, 156, 312);
    }

    private static void assertConfirmationGeometry(
            int height, int panel, int heading, int message,
            int warning, int error, int footer) {
        assertEquals(panel, LumiDeletedVersionsScreen.confirmationPanelOffset(height));
        assertEquals(heading, LumiDeletedVersionsScreen.confirmationHeadingOffset(height));
        assertEquals(message, LumiDeletedVersionsScreen.confirmationMessageOffset(height));
        assertEquals(warning, LumiDeletedVersionsScreen.confirmationWarningOffset(height));
        assertEquals(error, LumiDeletedVersionsScreen.confirmationErrorOffset(height));
        assertEquals(footer, LumiDeletedVersionsScreen.footerOffset(height));
        assertTrue(heading + 9 <= message);
        assertTrue(message + 9 <= warning);
        assertTrue(warning + 9 <= error);
        assertTrue(error + 9 <= footer);
    }
}
