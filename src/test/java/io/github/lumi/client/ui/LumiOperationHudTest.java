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
    void previewPreservesHeightDuringInterruptionsAndCanDisableMotion() {
        var motion = new LumiEffectPreview.Motion();
        motion.change(LumiEffectPreview.State.SAVE, 0);
        var opening = motion.frame(150_000_000L);
        assertTrue(opening.height() > 24 && opening.height() < 42);
        assertTrue(opening.opacity() > 0 && opening.opacity() < 1);
        motion.change(LumiEffectPreview.State.RESTORE, 150_000_000L);
        assertEquals(opening.height(), motion.frame(150_000_000L).height());
        assertEquals(50, motion.frame(450_000_000L).height());
        assertEquals(1, motion.frame(10_000_000_000L).progress());
        motion.animated = false;
        motion.change(LumiEffectPreview.State.ERROR, 500_000_000L);
        var still = motion.frame(500_000_000L);
        assertEquals(1, still.mix());
        assertEquals(42, still.height());
        assertEquals(LumiEffectPreview.State.ERROR, still.state());
        motion.reset();
        assertEquals(0, motion.frame(1_000_000_000L).opacity());
    }

    @Test
    void previewGroupsRepeatedUndoWithoutRestartingTheEntrance() {
        var motion = new LumiEffectPreview.Motion();
        motion.change(LumiEffectPreview.State.UNDO, 0);
        motion.change(LumiEffectPreview.State.UNDO, 400_000_000L);
        var repeated = motion.frame(400_000_000L);
        assertEquals(2, repeated.repetitions());
        assertEquals(1, repeated.mix());
        assertEquals(42, repeated.height());
        motion.change(LumiEffectPreview.State.REDO, 500_000_000L);
        assertEquals(1, motion.frame(500_000_000L).repetitions());
        assertEquals(2, motion.frame(500_000_000L).previousRepetitions());
        motion.change(LumiEffectPreview.State.RESTORE, 600_000_000L);
        assertEquals(0, motion.frame(1_000_000_000L).progress());
        assertTrue(motion.frame(1_500_000_000L).progress() > 0);
        motion.reset();
        motion.change(LumiEffectPreview.State.UNDO, 1_600_000_000L);
        assertEquals(1, motion.frame(1_600_000_000L).repetitions());
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
