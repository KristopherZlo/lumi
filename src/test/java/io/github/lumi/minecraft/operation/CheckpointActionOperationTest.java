package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.WorldActionScope;
import io.github.lumi.domain.model.WorldActionCheckpoint;
import io.github.lumi.domain.service.LiveActionJournal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckpointActionOperationTest {
    private static final UUID PLAYER = new UUID(0, 41);

    @Test
    void releasesTemporaryDataOnceAfterSafeCloseAndKeepsItForRecovery() throws Exception {
        for (var terminal : MutationTerminalState.values()) {
            var journal = checkpointJournal();
            var restore = new ControlledMutation(terminal);
            var released = new java.util.concurrent.atomic.AtomicInteger();
            var operation = new CheckpointActionOperation(journal,
                    journal.prepareUndo(PLAYER).orElseThrow(), restore, released::incrementAndGet);
            operation.advance(Long.MAX_VALUE);

            operation.close();
            operation.close();

            assertEquals(terminal == MutationTerminalState.DEGRADED ? 0 : 1, released.get());
        }
    }

    @Test
    void advancesStackOnlyAfterSuccessfulRestore() throws Exception {
        LiveActionJournal journal = checkpointJournal();
        var plan = journal.prepareUndo(PLAYER).orElseThrow();
        ControlledMutation restore = new ControlledMutation(MutationTerminalState.SUCCEEDED);
        var operation = new CheckpointActionOperation(journal, plan, restore);

        operation.advance(Long.MAX_VALUE);

        assertEquals(MutationTerminalState.SUCCEEDED, operation.terminalState());
        assertTrue(journal.prepareUndo(PLAYER).isEmpty());
        assertTrue(journal.prepareRedo(PLAYER).isPresent());
    }

    @Test
    void keepsCheckpointOnSourceStackAfterFailedRestore() throws Exception {
        LiveActionJournal journal = checkpointJournal();
        var plan = journal.prepareUndo(PLAYER).orElseThrow();
        ControlledMutation restore = new ControlledMutation(MutationTerminalState.FAILED);
        var operation = new CheckpointActionOperation(journal, plan, restore);

        operation.advance(Long.MAX_VALUE);

        assertEquals(MutationTerminalState.FAILED, operation.terminalState());
        assertEquals(plan, journal.prepareUndo(PLAYER).orElseThrow());
        assertTrue(journal.prepareRedo(PLAYER).isEmpty());
    }

    private static LiveActionJournal checkpointJournal() {
        LiveActionJournal journal = new LiveActionJournal();
        journal.pushCheckpoint(PLAYER, new WorldActionCheckpoint(
                new BranchName("main"), id('2'), id('1'), new BranchRef(
                        new BranchName("hidden/session-undo/test"), id('2'), 0), WorldActionScope.empty()));
        return journal;
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }

    private static final class ControlledMutation implements DimensionMutation {
        private final MutationTerminalState terminal;
        private boolean complete;
        private boolean closed;

        private ControlledMutation(MutationTerminalState terminal) {
            this.terminal = terminal;
        }

        @Override public void advance(long deadlineNanos) { complete = true; }
        @Override public boolean isTerminal() { return complete; }
        @Override public boolean isSafeToRelease() {
            return closed || complete && terminal != MutationTerminalState.DEGRADED;
        }
        @Override public void close() { closed = true; }
        @Override public MutationTerminalState terminalState() { return terminal; }
        @Override public Optional<Throwable> failure() { return Optional.empty(); }
    }
}
