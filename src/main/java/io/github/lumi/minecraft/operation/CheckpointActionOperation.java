package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.world.RestoreApplyStatistics;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Moves one durable checkpoint action only after its Restore finishes exactly. */
public final class CheckpointActionOperation implements DimensionMutation {
    private final LiveActionJournal journal;
    private final LiveActionJournal.Plan plan;
    private final DimensionMutation restore;
    private final Runnable releaseTemporaryData;
    private boolean completed;
    private boolean closed;

    public CheckpointActionOperation(
            LiveActionJournal journal,
            LiveActionJournal.Plan plan,
            DimensionMutation restore) {
        this(journal, plan, restore, () -> { });
    }

    public CheckpointActionOperation(
            LiveActionJournal journal, LiveActionJournal.Plan plan,
            DimensionMutation restore, Runnable releaseTemporaryData) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.restore = Objects.requireNonNull(restore, "restore");
        this.releaseTemporaryData = Objects.requireNonNull(releaseTemporaryData, "releaseTemporaryData");
        if (plan.checkpoint().isEmpty()) {
            throw new IllegalArgumentException(
                    "Checkpoint operation requires a checkpoint plan");
        }
    }

    @Override
    public void advance(long deadlineNanos) throws IOException {
        restore.advance(deadlineNanos);
        if (!completed && restore.isTerminal()
                && restore.terminalState() == MutationTerminalState.SUCCEEDED) {
            journal.complete(plan);
            completed = true;
        }
    }

    @Override public boolean requiresFreeze() { return true; }
    @Override public boolean isTerminal() { return restore.isTerminal(); }
    @Override public boolean isSafeToRelease() { return restore.isSafeToRelease(); }
    @Override public MutationTerminalState terminalState() { return restore.terminalState(); }
    @Override public Optional<Throwable> failure() { return restore.failure(); }
    @Override public OperationProgress progress() { return restore.progress(); }
    @Override public Optional<RestoreApplyStatistics> restoreStatistics() {
        return restore.restoreStatistics();
    }
    @Override public MutationTerminalState unhandledFailureState() {
        return restore.isTerminal()
                && restore.terminalState() == MutationTerminalState.SUCCEEDED
                ? MutationTerminalState.DEGRADED
                : restore.unhandledFailureState();
    }
    @Override public boolean cancel() throws IOException { return restore.cancel(); }
    @Override public void close() throws IOException {
        if (closed) return;
        boolean safe = restore.isSafeToRelease();
        try {
            restore.close();
        } finally {
            closed = true;
            if (safe) releaseTemporaryData.run();
        }
    }
}
