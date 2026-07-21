package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void movesIndeterminateProgressBackAndForth() {
        assertEquals(0, LumiOperationHud.indeterminateOffset(0, 40));
        assertEquals(40, LumiOperationHud.indeterminateOffset(800, 40));
        assertEquals(0, LumiOperationHud.indeterminateOffset(1_600, 40));
    }

    @Test
    void honorsTheActiveWorkspaceHudSetting() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiOperationHud.java"));

        assertTrue(source.contains("WorkspaceView::active"));
        assertTrue(source.contains("WorkspaceView::workspaceHudEnabled"));
        assertTrue(source.contains("LumiHotkeys.actionModifierDown("));
        assertTrue(source.contains("result.workspace().total()"));
        assertTrue(source.contains("Integer.toString(snapshot.pendingKeys())"));
        assertFalse(source.contains("\"…\""));
        assertTrue(source.contains("drawClipped(graphics,"));
        assertTrue(source.contains("\"key.lumi.quick_rollback\""));
        assertTrue(source.contains("\"key.lumi.open_dashboard\""));
        assertFalse(source.contains("\"Alt+R rollback\""));
    }
}
