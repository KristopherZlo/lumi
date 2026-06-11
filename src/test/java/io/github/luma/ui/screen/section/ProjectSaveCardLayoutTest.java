package io.github.luma.ui.screen.section;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectSaveCardLayoutTest {

    @Test
    void wideScreensUseInlineActions() {
        assertEquals(ProjectSaveCardLayout.Placement.INLINE_RIGHT, ProjectSaveCardLayout.placementFor(960));
    }

    @Test
    void defaultMinecraftWindowUsesInlineActions() {
        assertEquals(ProjectSaveCardLayout.Placement.INLINE_RIGHT, ProjectSaveCardLayout.placementFor(854));
    }

    @Test
    void narrowScreensKeepActionsBelowContent() {
        assertEquals(ProjectSaveCardLayout.Placement.STACKED_BELOW, ProjectSaveCardLayout.placementFor(720));
    }

    @Test
    void normalCardKeepsThreeActiveActions() {
        List<ProjectSaveCardLayout.ActionState> actions = ProjectSaveCardLayout.actions(true, false);

        assertEquals(3, actions.size());
        assertEquals(ProjectSaveCardLayout.Action.OPEN, actions.get(0).action());
        assertEquals(ProjectSaveCardLayout.Action.RESTORE, actions.get(1).action());
        assertEquals(ProjectSaveCardLayout.Action.CREATE_VARIANT, actions.get(2).action());
        assertTrue(actions.get(0).active());
        assertTrue(actions.get(1).active());
        assertTrue(actions.get(2).active());
    }

    @Test
    void activeOperationDisablesRestoreAndVariantCreationOnly() {
        List<ProjectSaveCardLayout.ActionState> actions = ProjectSaveCardLayout.actions(true, true);

        assertTrue(actions.get(0).active());
        assertFalse(actions.get(1).active());
        assertFalse(actions.get(2).active());
    }

    @Test
    void missingVariantDisablesRestoreButKeepsVariantCreationAvailable() {
        List<ProjectSaveCardLayout.ActionState> actions = ProjectSaveCardLayout.actions(false, false);

        assertTrue(actions.get(0).active());
        assertFalse(actions.get(1).active());
        assertTrue(actions.get(2).active());
    }
}
