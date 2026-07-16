package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.MutationTerminalState;
import io.github.lumi.minecraft.operation.OperationProgress;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Records one multi-tick server mutation as one session-only live action. */
public final class LiveRecordedMutation implements DimensionMutation {
    private final LiveActionJournal journal;
    private final DimensionMutation delegate;
    private final UUID action;
    private boolean closed;

    public LiveRecordedMutation(
            LiveActionJournal journal, UUID player, DimensionMutation delegate) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        action = journal.begin(Objects.requireNonNull(player, "player"));
    }

    public UUID actionId() {
        return action;
    }

    @Override
    public void advance(long deadlineNanos) throws IOException {
        try (var ignored = DirectLiveActionContext.resume(journal, action)) {
            delegate.advance(deadlineNanos);
        }
        if (delegate.isTerminal() && !closed) {
            journal.close(action);
            closed = true;
        }
    }

    @Override public boolean isTerminal() { return delegate.isTerminal(); }
    @Override public boolean isSafeToRelease() { return delegate.isSafeToRelease(); }
    @Override public boolean requiresFreeze() { return delegate.requiresFreeze(); }
    @Override public MutationTerminalState terminalState() { return delegate.terminalState(); }
    @Override public Optional<Throwable> failure() { return delegate.failure(); }
    @Override public MutationTerminalState unhandledFailureState() {
        return delegate.unhandledFailureState();
    }
    @Override public OperationProgress progress() { return delegate.progress(); }

    @Override
    public boolean cancel() throws IOException {
        boolean cancelled = delegate.cancel();
        if (cancelled) {
            closeAction();
        }
        return cancelled;
    }

    @Override
    public void close() throws IOException {
        try {
            delegate.close();
        } finally {
            closeAction();
        }
    }

    private void closeAction() {
        if (!closed) {
            journal.close(action);
            closed = true;
        }
    }
}
