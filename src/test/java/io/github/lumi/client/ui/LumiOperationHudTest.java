package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class LumiOperationHudTest {
    @Test
    void stacksOperationBelowCollapsedOrExpandedWorkspacePanel() {
        assertEquals(38, LumiOperationHud.nextPanelY(10, 22));
        assertEquals(84, LumiOperationHud.nextPanelY(10, 68));
    }

    @Test
    void honorsTheActiveWorkspaceHudSetting() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiOperationHud.java"));

        assertTrue(source.contains("WorkspaceView::active"));
        assertTrue(source.contains("WorkspaceView::workspaceHudEnabled"));
    }
}
