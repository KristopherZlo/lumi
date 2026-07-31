package io.github.lumi.minecraft.operation;

import io.github.lumi.minecraft.world.RestoreApplyStatistics;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Resolves current refs and scope only when queued work becomes active. */
public final class DeferredDimensionMutation implements DimensionMutation {
    private final Activation activation;
    private final Factory factory;
    private final boolean initialFreeze;
    private DimensionMutation delegate;
    private Throwable failure;
    private boolean cancelled;

    public DeferredDimensionMutation(Factory factory) {
        this(false, () -> { }, factory);
    }

    public DeferredDimensionMutation(Activation activation, Factory factory) {
        this(false, activation, factory);
    }

    public DeferredDimensionMutation(boolean initialFreeze, Factory factory) {
        this(initialFreeze, () -> { }, factory);
    }

    public DeferredDimensionMutation(
            boolean initialFreeze, Activation activation, Factory factory) {
        this.initialFreeze = initialFreeze;
        this.activation = Objects.requireNonNull(activation, "activation");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public boolean requiresFreeze() {
        return delegate == null ? initialFreeze : delegate.requiresFreeze();
    }

    @Override
    public void advance(long deadlineNanos) throws IOException {
        if (failure != null || cancelled) {
            return;
        }
        if (delegate == null) {
            try {
                activation.validate();
                delegate = Objects.requireNonNull(factory.create(), "deferred mutation");
            } catch (IOException | RuntimeException failed) {
                failure = failed;
            }
            return;
        }
        delegate.advance(deadlineNanos);
    }

    @Override
    public boolean isTerminal() {
        return cancelled || failure != null || delegate != null && delegate.isTerminal();
    }

    @Override
    public boolean isSafeToRelease() {
        return cancelled || failure != null || delegate != null && delegate.isSafeToRelease();
    }

    @Override
    public MutationTerminalState terminalState() {
        if (cancelled) {
            return MutationTerminalState.CANCELLED;
        }
        if (failure != null) {
            return MutationTerminalState.FAILED;
        }
        if (delegate == null) {
            throw new IllegalStateException("Deferred mutation has not started");
        }
        return delegate.terminalState();
    }

    @Override
    public Optional<Throwable> failure() {
        return failure != null ? Optional.of(failure)
                : delegate == null ? Optional.empty() : delegate.failure();
    }

    @Override
    public Optional<String> completionMessage() {
        return delegate == null ? Optional.empty() : delegate.completionMessage();
    }

    @Override public OperationProgress progress() {
        return cancelled ? OperationProgress.indeterminate("Cancelled")
                : delegate == null ? OperationProgress.indeterminate("Resolving request")
                : delegate.progress();
    }

    @Override public Optional<RestoreApplyStatistics> restoreStatistics() {
        return delegate == null ? Optional.empty() : delegate.restoreStatistics();
    }

    @Override
    public MutationTerminalState unhandledFailureState() {
        return delegate == null
                ? MutationTerminalState.FAILED : delegate.unhandledFailureState();
    }

    @Override
    public boolean cancel() throws IOException {
        if (isTerminal()) {
            return false;
        }
        if (delegate == null) {
            cancelled = true;
            return true;
        }
        return delegate.cancel();
    }

    @Override
    public void close() throws IOException {
        cancelled = true;
        if (delegate != null) {
            delegate.close();
        }
    }

    @FunctionalInterface
    public interface Activation {
        void validate() throws IOException;

        default Activation and(Activation next) {
            Objects.requireNonNull(next, "next");
            return () -> {
                validate();
                next.validate();
            };
        }
    }

    @FunctionalInterface
    public interface Factory {
        DimensionMutation create() throws IOException;
    }
}
