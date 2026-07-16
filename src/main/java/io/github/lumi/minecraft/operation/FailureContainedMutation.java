package io.github.lumi.minecraft.operation;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Converts an escaped operation failure into one stable terminal outcome. */
final class FailureContainedMutation implements DimensionMutation {
    private final DimensionMutation delegate;
    private Throwable failure;
    private MutationTerminalState failureState;

    FailureContainedMutation(DimensionMutation delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    DimensionMutation source() {
        return delegate;
    }

    DimensionMutation outcome() {
        return failure == null ? delegate : this;
    }

    @Override
    public boolean requiresFreeze() {
        return delegate.requiresFreeze();
    }

    @Override
    public void advance(long deadlineNanos) {
        if (failure != null) {
            return;
        }
        try {
            delegate.advance(deadlineNanos);
        } catch (IOException | RuntimeException failed) {
            failure = failed;
            failureState = requireFailureState(delegate.unhandledFailureState());
        }
    }

    private static MutationTerminalState requireFailureState(MutationTerminalState state) {
        if (state != MutationTerminalState.FAILED
                && state != MutationTerminalState.DEGRADED) {
            throw new IllegalStateException(
                    "Unhandled failures must terminate as FAILED or DEGRADED");
        }
        return state;
    }

    @Override
    public boolean isTerminal() {
        return failure != null || delegate.isTerminal();
    }

    @Override
    public boolean isSafeToRelease() {
        return failure != null
                ? failureState == MutationTerminalState.FAILED
                : delegate.isSafeToRelease();
    }

    @Override
    public MutationTerminalState terminalState() {
        return failure == null ? delegate.terminalState() : failureState;
    }

    @Override
    public Optional<Throwable> failure() {
        return failure == null ? delegate.failure() : Optional.of(failure);
    }

    @Override
    public OperationProgress progress() {
        return delegate.progress();
    }
}
