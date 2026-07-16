package io.github.lumi.minecraft.operation;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Resolves current refs and scope only when queued work becomes active. */
public final class DeferredDimensionMutation implements DimensionMutation {
    private final Factory factory;
    private DimensionMutation delegate;
    private Throwable failure;

    public DeferredDimensionMutation(Factory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public boolean requiresFreeze() {
        return delegate != null && delegate.requiresFreeze();
    }

    @Override
    public void advance(long deadlineNanos) throws IOException {
        if (failure != null) {
            return;
        }
        if (delegate == null) {
            try {
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
        return failure != null || delegate != null && delegate.isTerminal();
    }

    @Override
    public boolean isSafeToRelease() {
        return failure != null || delegate != null && delegate.isSafeToRelease();
    }

    @Override
    public MutationTerminalState terminalState() {
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

    @FunctionalInterface
    public interface Factory {
        DimensionMutation create() throws IOException;
    }
}
