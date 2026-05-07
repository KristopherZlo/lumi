package io.github.luma.client.selection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionToolTeachingStateTest {

    @Test
    void startsOnlyWhenInputToolAndHintAreReady() {
        SelectionToolTeachingState state = new SelectionToolTeachingState(2);
        state.observeHintAllowed(true);

        assertFalse(state.shouldStart(false, true, true));
        assertFalse(state.shouldStart(true, false, true));
        assertFalse(state.shouldStart(true, true, false));
        assertTrue(state.shouldStart(true, true, true));
    }

    @Test
    void completesAfterDisplayTicksAndDoesNotRepeatInSameHintCycle() {
        SelectionToolTeachingState state = new SelectionToolTeachingState(2);
        state.observeHintAllowed(true);

        state.start();

        assertTrue(state.active());
        assertFalse(state.tickDisplay());
        assertTrue(state.tickDisplay());
        assertFalse(state.active());
        assertFalse(state.shouldStart(true, true, true));
    }

    @Test
    void resetHintCycleCanShowAgain() {
        SelectionToolTeachingState state = new SelectionToolTeachingState(1);
        state.observeHintAllowed(true);
        state.start();
        assertTrue(state.tickDisplay());

        state.observeHintAllowed(false);
        state.observeHintAllowed(true);

        assertTrue(state.shouldStart(true, true, true));
    }
}
