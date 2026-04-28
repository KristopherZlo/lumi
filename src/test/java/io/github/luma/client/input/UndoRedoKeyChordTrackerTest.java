package io.github.luma.client.input;

import io.github.luma.ui.overlay.RecentChangesOverlayCoordinator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoRedoKeyChordTrackerTest {

    @Test
    void modifierPlusUndoRequestsUndoOnce() {
        UndoRedoKeyChordTracker tracker = new UndoRedoKeyChordTracker();

        UndoRedoKeyChordTracker.TickResult first = tracker.tick(true, true, true, false, false, false);
        UndoRedoKeyChordTracker.TickResult held = tracker.tick(true, true, true, false, false, false);

        assertTrue(first.undoPressed());
        assertFalse(first.redoPressed());
        assertFalse(held.undoPressed());
        assertFalse(held.redoPressed());
    }

    @Test
    void inactiveInputReturnsIdleAndClearsHeldState() {
        UndoRedoKeyChordTracker tracker = new UndoRedoKeyChordTracker();

        tracker.tick(true, true, true, false, false, false);
        UndoRedoKeyChordTracker.TickResult inactive = tracker.tick(false, true, true, false, true, false);
        UndoRedoKeyChordTracker.TickResult activeAgain = tracker.tick(true, true, true, false, false, false);

        assertFalse(inactive.undoPressed());
        assertFalse(inactive.redoPressed());
        assertEquals(RecentChangesOverlayCoordinator.PreviewTarget.UNDO, inactive.previewTarget());
        assertTrue(activeAgain.undoPressed());
    }

    @Test
    void simultaneousUndoAndRedoPrioritizesUndoOnly() {
        UndoRedoKeyChordTracker tracker = new UndoRedoKeyChordTracker();

        UndoRedoKeyChordTracker.TickResult result = tracker.tick(true, true, true, true, false, false);

        assertTrue(result.undoPressed());
        assertFalse(result.redoPressed());
        assertEquals(RecentChangesOverlayCoordinator.PreviewTarget.UNDO, result.previewTarget());
    }

    @Test
    void redoPreviewRequiresRedoChordWithoutUndoChord() {
        UndoRedoKeyChordTracker tracker = new UndoRedoKeyChordTracker();

        UndoRedoKeyChordTracker.TickResult redo = tracker.tick(true, true, false, true, false, false);

        assertFalse(redo.undoPressed());
        assertTrue(redo.redoPressed());
        assertEquals(RecentChangesOverlayCoordinator.PreviewTarget.REDO, redo.previewTarget());
    }

    @Test
    void modifierMustBeHeldForClicksToTriggerRequests() {
        UndoRedoKeyChordTracker tracker = new UndoRedoKeyChordTracker();

        UndoRedoKeyChordTracker.TickResult result = tracker.tick(true, false, true, true, true, true);

        assertFalse(result.undoPressed());
        assertFalse(result.redoPressed());
        assertEquals(RecentChangesOverlayCoordinator.PreviewTarget.UNDO, result.previewTarget());
    }
}
