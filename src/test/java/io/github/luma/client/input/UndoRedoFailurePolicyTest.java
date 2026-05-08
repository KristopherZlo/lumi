package io.github.luma.client.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoRedoFailurePolicyTest {

    @Test
    void settlingFailureStaysRetryableInsteadOfGenericFailure() {
        UndoRedoFailurePolicy policy = new UndoRedoFailurePolicy();

        String statusKey = policy.statusKey(
                new IllegalStateException("Redstone or piston fallout is still settling; try undo/redo again in a moment"),
                true
        );

        assertEquals("luma.status.undo_redo_settling", statusKey);
        assertTrue(policy.shouldRetry(statusKey));
    }

    @Test
    void busyWorldOperationStaysRetryable() {
        UndoRedoFailurePolicy policy = new UndoRedoFailurePolicy();

        String statusKey = policy.statusKey(
                new IllegalStateException("Another world operation is already running"),
                false
        );

        assertEquals("luma.status.world_operation_busy", statusKey);
        assertTrue(policy.shouldRetry(statusKey));
    }

    @Test
    void unavailableUndoAndRedoAreNotRetried() {
        UndoRedoFailurePolicy policy = new UndoRedoFailurePolicy();

        String undoStatus = policy.statusKey(new IllegalArgumentException("No Lumi action is available to undo"), true);
        String redoStatus = policy.statusKey(new IllegalArgumentException("No Lumi action is available to redo"), false);

        assertEquals("luma.status.undo_unavailable", undoStatus);
        assertEquals("luma.status.redo_unavailable", redoStatus);
        assertFalse(policy.shouldRetry(undoStatus));
        assertFalse(policy.shouldRetry(redoStatus));
    }

    @Test
    void unknownFailureRemainsGenericAndNonRetryable() {
        UndoRedoFailurePolicy policy = new UndoRedoFailurePolicy();

        String statusKey = policy.statusKey(new IllegalStateException("Unexpected startup failure"), true);

        assertEquals("luma.status.operation_failed", statusKey);
        assertFalse(policy.shouldRetry(statusKey));
    }
}
