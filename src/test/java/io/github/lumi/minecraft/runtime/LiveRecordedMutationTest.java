package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveRecordedMutationTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final BlockPosition POSITION = new BlockPosition(1, 2, 3);

    @Test
    void recordsMultiTickDelegateAsOneClosedAction() throws Exception {
        LiveActionJournal journal = new LiveActionJournal();
        RecordingMutation delegate = new RecordingMutation(journal);
        LiveRecordedMutation operation = new LiveRecordedMutation(journal, PLAYER, delegate);

        operation.advance(Long.MAX_VALUE);
        operation.advance(Long.MAX_VALUE);

        var undo = journal.prepareUndo(PLAYER).orElseThrow();
        assertEquals(operation.actionId(), undo.actionId());
        assertEquals(block("gold_block"), undo.expected().get(POSITION));
        assertEquals(block("stone"), undo.replacement().get(POSITION));
    }

    @Test
    void dropsEmptyCompletedMutation() throws Exception {
        LiveActionJournal journal = new LiveActionJournal();
        LiveRecordedMutation operation = new LiveRecordedMutation(
                journal, PLAYER, new EmptyMutation());

        operation.advance(Long.MAX_VALUE);

        assertEquals(Optional.empty(), journal.prepareUndo(PLAYER));
    }

    @Test
    void propagatesCancellationAndClosesTheLiveAction() throws Exception {
        LiveActionJournal journal = new LiveActionJournal();
        CancellableMutation delegate = new CancellableMutation();
        LiveRecordedMutation operation = new LiveRecordedMutation(journal, PLAYER, delegate);

        assertTrue(operation.cancel());

        assertEquals(MutationTerminalState.CANCELLED, operation.terminalState());
        assertEquals(Optional.empty(), journal.prepareUndo(PLAYER));
    }

    @Test
    void dropsRecordedStateWhenTheDelegateFails() throws Exception {
        LiveActionJournal journal = new LiveActionJournal();
        LiveRecordedMutation operation = new LiveRecordedMutation(
                journal, PLAYER, new FailedRecordingMutation(journal));

        operation.advance(Long.MAX_VALUE);

        assertEquals(MutationTerminalState.FAILED, operation.terminalState());
        assertEquals(Optional.empty(), journal.prepareUndo(PLAYER));
    }

    private static BlockSnapshot block(String id) {
        return new BlockSnapshot("minecraft:" + id, Optional.empty());
    }

    private static final class RecordingMutation implements DimensionMutation {
        private final LiveActionJournal journal;
        private int step;

        private RecordingMutation(LiveActionJournal journal) {
            this.journal = journal;
        }

        @Override
        public void advance(long deadlineNanos) {
            UUID action = DirectLiveActionContext.current(journal).orElseThrow();
            journal.record(action, POSITION,
                    step == 0 ? block("stone") : block("dirt"),
                    step == 0 ? block("dirt") : block("gold_block"));
            step++;
        }

        @Override public boolean isTerminal() { return step == 2; }
        @Override public boolean isSafeToRelease() { return isTerminal(); }
    }

    private static final class EmptyMutation implements DimensionMutation {
        private boolean complete;
        @Override public void advance(long deadlineNanos) { complete = true; }
        @Override public boolean isTerminal() { return complete; }
        @Override public boolean isSafeToRelease() { return complete; }
    }

    private static final class CancellableMutation implements DimensionMutation {
        private boolean cancelled;

        @Override public void advance(long deadlineNanos) { }
        @Override public boolean cancel() {
            cancelled = true;
            return true;
        }
        @Override public boolean isTerminal() { return cancelled; }
        @Override public boolean isSafeToRelease() { return cancelled; }
        @Override public MutationTerminalState terminalState() {
            return MutationTerminalState.CANCELLED;
        }
    }

    private static final class FailedRecordingMutation implements DimensionMutation {
        private final LiveActionJournal journal;
        private boolean failed;

        private FailedRecordingMutation(LiveActionJournal journal) {
            this.journal = journal;
        }

        @Override
        public void advance(long deadlineNanos) {
            journal.record(
                    DirectLiveActionContext.current(journal).orElseThrow(),
                    POSITION, block("stone"), block("air"));
            failed = true;
        }

        @Override public boolean isTerminal() { return failed; }
        @Override public boolean isSafeToRelease() { return failed; }
        @Override public MutationTerminalState terminalState() {
            return MutationTerminalState.FAILED;
        }
    }
}
