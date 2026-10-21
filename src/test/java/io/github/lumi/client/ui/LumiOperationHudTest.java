package io.github.lumi.client.ui;
// Checks the shared source before platform API substitutions.
//~ !native-api
//~ !native-types

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class LumiOperationHudTest {
    @Test
    void boundsDetailedFeedbackAndFitsTheProgressTrackInsideOnePanel() {
        assertEquals(24, LumiHudPanel.contentHeight(true, 0, false, 0));
        assertEquals(42, LumiHudPanel.contentHeight(false, 1, false, 0));
        assertEquals(50, LumiHudPanel.contentHeight(false, 1, true, 0));
        assertEquals(LumiHudPanel.contentHeight(false, 3, true, 3),
                LumiHudPanel.contentHeight(false, 500, true, 500));
        assertTrue(LumiHudPanel.contentHeight(false, 500, true, 500) < 120);
    }

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
        String source = io.github.lumi.ProductionJavaSource.read(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiOperationHud.java"));

        assertTrue(source.contains("WorkspaceView::active"));
        assertTrue(source.contains(
                "workspace.hudDisplayMode() == HudDisplayMode.GUI"));
        assertTrue(source.contains("if (!usesGui()) return;"));
        assertTrue(source.contains("ScreenEvents.AFTER_INIT"));
        assertTrue(source.contains("ScreenEvents.afterRender(screen)")
                || source.contains("ScreenEvents.afterExtract(screen)"));
        assertTrue(source.contains("client.screen == current"));
        assertTrue(source.contains("notifications.visible()"));
        assertTrue(source.contains("ClientPlayConnectionEvents.DISCONNECT"));
        assertFalse(source.contains("mouseClicked("));
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
