package io.github.luma.client.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumiActionKeyChordTrackerTest {

    @Test
    void modifierChordRequestsOneSelectionStep() {
        LumiActionKeyChordTracker tracker = new LumiActionKeyChordTracker();

        LumiActionKeyChordTracker.TickResult first = tracker.tick(true, true, true, false, false, false);
        LumiActionKeyChordTracker.TickResult held = tracker.tick(true, true, true, false, false, false);

        assertTrue(first.undoPressed());
        assertTrue(first.chordActive());
        assertFalse(held.undoPressed());
        assertTrue(held.chordActive());
    }

    @Test
    void inactiveInputClearsHeldState() {
        LumiActionKeyChordTracker tracker = new LumiActionKeyChordTracker();
        tracker.tick(true, true, true, false, false, false);

        LumiActionKeyChordTracker.TickResult inactive = tracker.tick(false, true, true, false, true, false);
        LumiActionKeyChordTracker.TickResult activeAgain = tracker.tick(true, true, true, false, false, false);

        assertFalse(inactive.chordActive());
        assertTrue(activeAgain.undoPressed());
    }

    @Test
    void simultaneousDirectionsPreferUndo() {
        LumiActionKeyChordTracker.TickResult result = new LumiActionKeyChordTracker()
                .tick(true, true, true, true, false, false);

        assertTrue(result.undoPressed());
        assertFalse(result.redoPressed());
    }
}
