package io.github.luma.integration.axiom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AxiomNativeUndoRedoGuardTest {

    @BeforeEach
    void setUp() {
        AxiomNativeUndoRedoGuard.clearForTests();
    }

    @AfterEach
    void tearDown() {
        AxiomNativeUndoRedoGuard.clearForTests();
    }

    @Test
    void consumesExpectedNativeReplayOnce() {
        AxiomNativeUndoRedoGuard.expectNativeReplay();

        assertEquals(1, AxiomNativeUndoRedoGuard.pendingNativeReplays());
        assertTrue(AxiomNativeUndoRedoGuard.consumeExpectedNativeReplay());
        assertEquals(0, AxiomNativeUndoRedoGuard.pendingNativeReplays());
        assertFalse(AxiomNativeUndoRedoGuard.consumeExpectedNativeReplay());
    }

    @Test
    void cancelsMatchingExpectedReplay() {
        int first = AxiomNativeUndoRedoGuard.expectNativeReplay();
        AxiomNativeUndoRedoGuard.expectNativeReplay();

        AxiomNativeUndoRedoGuard.cancelExpectedNativeReplay(first);

        assertEquals(1, AxiomNativeUndoRedoGuard.pendingNativeReplays());
        assertTrue(AxiomNativeUndoRedoGuard.consumeExpectedNativeReplay());
        assertEquals(0, AxiomNativeUndoRedoGuard.pendingNativeReplays());
    }
}
