package io.github.lumi.minecraft.operation;

import io.github.lumi.minecraft.world.RestoreApplyStatistics;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Converts an escaped operation failure into one stable terminal outcome. */
final class FailureContainedMutation implements DimensionMutation {
    private final DimensionMutation delegate;
    private DeferredDimensionMutation.Activation activation;
    private Throwable failure;
    private MutationTerminalState failureState;
    private boolean started;

    FailureContainedMutation(DimensionMutation delegate) {
        this(delegate, () -> { });
    }

    FailureContainedMutation(
            DimensionMutation delegate,
            DeferredDimensionMutation.Activation activation) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.activation = Objects.requireNonNull(activation, "activation");
    }

    DimensionMutation source() {
        return delegate;
    }

    DimensionMutation outcome() {
        return failure == null ? delegate : this;
    }

    void requireActivation(DeferredDimensionMutation.Activation added) {
        if (started || failure != null) {
            throw new IllegalStateException("Mutation already crossed its activation boundary");
        }
        activation = activation.and(added);
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
        if (!started) {
            try {
                activation.validate();
                started = true;
            } catch (IOException | RuntimeException rejected) {
                fail(rejected, MutationTerminalState.FAILED);
                return;
            }
        }
        try {
            delegate.advance(deadlineNanos);
        } catch (IOException | RuntimeException failed) {
            fail(failed, requireFailureState(delegate.unhandledFailureState()));
        }
    }

    private void fail(Throwable failed, MutationTerminalState state) {
        failure = failed;
        failureState = state;
        try {
            delegate.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
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

    @Override public Optional<RestoreApplyStatistics> restoreStatistics() {
        return delegate.restoreStatistics();
    }

    @Override
    public boolean cancel() throws IOException {
        return failure == null && delegate.cancel();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
