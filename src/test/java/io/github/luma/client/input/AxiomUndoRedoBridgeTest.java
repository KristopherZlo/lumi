package io.github.luma.client.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AxiomUndoRedoBridgeTest {

    @Test
    void dispatchesUndoThroughAxiomHistory() {
        FakeAxiomInvoker invoker = new FakeAxiomInvoker(2, 4);
        AxiomUndoRedoBridge bridge = new AxiomUndoRedoBridge(invoker);

        AxiomUndoRedoBridge.DispatchResult result = bridge.dispatch(true);
        try {
            assertTrue(result.dispatched());
            assertEquals(1, invoker.position());
            assertEquals(1, invoker.dispatchCalls());
            assertTrue(invoker.lastUndo());
        } finally {
            result.clearUnconsumedReplayExpectation();
        }
    }

    @Test
    void dispatchesRedoThroughAxiomHistory() {
        FakeAxiomInvoker invoker = new FakeAxiomInvoker(1, 4);
        AxiomUndoRedoBridge bridge = new AxiomUndoRedoBridge(invoker);

        AxiomUndoRedoBridge.DispatchResult result = bridge.dispatch(false);
        try {
            assertTrue(result.dispatched());
            assertEquals(2, invoker.position());
            assertEquals(1, invoker.dispatchCalls());
            assertFalse(invoker.lastUndo());
        } finally {
            result.clearUnconsumedReplayExpectation();
        }
    }

    @Test
    void fallsBackWhenAxiomDispatcherIsUnavailable() {
        FakeAxiomInvoker invoker = new FakeAxiomInvoker(1, 2);
        invoker.available(false);
        AxiomUndoRedoBridge bridge = new AxiomUndoRedoBridge(invoker);

        AxiomUndoRedoBridge.DispatchResult result = bridge.dispatch(true);

        assertFalse(result.dispatched());
        assertEquals(0, invoker.dispatchCalls());
    }

    @Test
    void fallsBackWhenAxiomHistoryCannotMove() {
        FakeAxiomInvoker invoker = new FakeAxiomInvoker(-1, 1);
        AxiomUndoRedoBridge bridge = new AxiomUndoRedoBridge(invoker);

        AxiomUndoRedoBridge.DispatchResult result = bridge.dispatch(true);

        assertFalse(result.dispatched());
        assertEquals(0, invoker.dispatchCalls());
    }

    @Test
    void fallsBackWhenAxiomHookDoesNotMoveHistoryPosition() {
        FakeAxiomInvoker invoker = new FakeAxiomInvoker(1, 2);
        invoker.moveOnDispatch(false);
        AxiomUndoRedoBridge bridge = new AxiomUndoRedoBridge(invoker);

        AxiomUndoRedoBridge.DispatchResult result = bridge.dispatch(true);

        assertFalse(result.dispatched());
        assertEquals(1, invoker.dispatchCalls());
    }

    private static final class FakeAxiomInvoker implements AxiomUndoRedoBridge.AxiomUndoRedoInvoker {

        private boolean available = true;
        private boolean moveOnDispatch = true;
        private int position;
        private final int historyDataCount;
        private int dispatchCalls;
        private boolean lastUndo;

        private FakeAxiomInvoker(int position, int historyDataCount) {
            this.position = position;
            this.historyDataCount = historyDataCount;
        }

        @Override
        public boolean available() {
            return this.available;
        }

        @Override
        public int historyPosition() {
            return this.position;
        }

        @Override
        public int historyDataCount() {
            return this.historyDataCount;
        }

        @Override
        public void dispatch(boolean undo) {
            this.dispatchCalls++;
            this.lastUndo = undo;
            if (this.moveOnDispatch) {
                this.position += undo ? -1 : 1;
            }
        }

        private int position() {
            return this.position;
        }

        private int dispatchCalls() {
            return this.dispatchCalls;
        }

        private boolean lastUndo() {
            return this.lastUndo;
        }

        private void available(boolean available) {
            this.available = available;
        }

        private void moveOnDispatch(boolean moveOnDispatch) {
            this.moveOnDispatch = moveOnDispatch;
        }
    }
}
