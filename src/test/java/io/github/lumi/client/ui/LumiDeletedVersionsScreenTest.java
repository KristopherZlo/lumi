package io.github.lumi.client.ui;

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
        assertTrue(source.contains("panelY + panelHeight - 28"));
        assertTrue(source.contains("visibleRows()"));
        assertTrue(source.contains("mouseScrolled("));
    }
}
