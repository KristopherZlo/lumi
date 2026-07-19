package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GarbageCollectionOperationTest {
    @Test
    void reservesTheQueueWhileInspectionRunsOffThread() {
        GarbageCollectionOperation operation = new GarbageCollectionOperation(
                false, () -> new GarbageCollectionOperation.Counts(2, 7), Runnable::run);

        assertFalse(operation.requiresFreeze());
        operation.advance(Long.MAX_VALUE);
        assertFalse(operation.isTerminal());
        operation.advance(Long.MAX_VALUE);

        assertTrue(operation.isTerminal());
        assertTrue(operation.isSafeToRelease());
        assertEquals(MutationTerminalState.SUCCEEDED, operation.terminalState());
        assertEquals(new GarbageCollectionOperation.Counts(2, 7),
                operation.counts().orElseThrow());
    }
}
