package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.service.LiveActionJournal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckpointActionOperationTest {
    private static final UUID PLAYER = new UUID(0, 41);

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
        journal.pushCheckpoint(PLAYER, new LiveActionJournal.Checkpoint(
                new BranchRef(new BranchName("main"), id('1'), 1),
                id('2'), new BranchRef(
                        new BranchName("hidden/session-undo/test"), id('2'), 0)));
        return journal;
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }

    private static final class ControlledMutation implements DimensionMutation {
        private final MutationTerminalState terminal;
        private boolean complete;

        private ControlledMutation(MutationTerminalState terminal) {
            this.terminal = terminal;
        }

        @Override public void advance(long deadlineNanos) { complete = true; }
        @Override public boolean isTerminal() { return complete; }
        @Override public boolean isSafeToRelease() { return complete; }
        @Override public MutationTerminalState terminalState() { return terminal; }
        @Override public Optional<Throwable> failure() { return Optional.empty(); }
    }
}
