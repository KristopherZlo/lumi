package io.github.lumi.minecraft.operation;

import io.github.lumi.minecraft.world.RestoreApplyStatistics;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** Owns an operation while it prepares off-thread, then validates and delegates under freeze. */
public final class BackgroundPreparedMutation<T extends DimensionMutation>
        implements DimensionMutation {
    private final PreparedMutationOwnership<T> preparation;
    private final FrozenValidation validation;
    private final PreparedDiscard<T> discard;
    private final boolean freezeDuringPreparation;
    private final boolean degradeOnFailure;
    private final Supplier<OperationProgress> preparationProgress;
    private T delegate;
    private Throwable failure;
    private boolean cancelled;

    public BackgroundPreparedMutation(
            CompletableFuture<T> preparation,
            FrozenValidation validation,
            PreparedDiscard<T> discard) {
        this(preparation, validation, discard, false);
    }

    public BackgroundPreparedMutation(
            CompletableFuture<T> preparation,
            FrozenValidation validation,
            PreparedDiscard<T> discard,
            boolean freezeDuringPreparation) {
        this(preparation, validation, discard, freezeDuringPreparation, false);
    }

    public BackgroundPreparedMutation(
            CompletableFuture<T> preparation,
            FrozenValidation validation,
            PreparedDiscard<T> discard,
            boolean freezeDuringPreparation,
            boolean degradeOnFailure) {
        this(preparation, validation, discard, freezeDuringPreparation,
                degradeOnFailure,
                () -> OperationProgress.indeterminate("Preparing world state"));
    }

    public BackgroundPreparedMutation(
            CompletableFuture<T> preparation,
            FrozenValidation validation,
            PreparedDiscard<T> discard,
            boolean freezeDuringPreparation,
            boolean degradeOnFailure,
            Supplier<OperationProgress> preparationProgress) {
        this.validation = Objects.requireNonNull(validation, "validation");
        this.discard = Objects.requireNonNull(discard, "discard");
        this.preparation = new PreparedMutationOwnership<>(
                Objects.requireNonNull(preparation, "preparation"), this::discardPrepared);
        this.freezeDuringPreparation = freezeDuringPreparation;
        this.degradeOnFailure = degradeOnFailure;
        this.preparationProgress = Objects.requireNonNull(
                preparationProgress, "preparationProgress");
    }

    @Override
    public boolean requiresFreeze() {
        return freezeDuringPreparation || delegate != null || (preparation.isDone()
                && !preparation.isCompletedExceptionally() && !preparation.isCancelled());
    }

    @Override
    public void advance(long deadlineNanos) throws IOException {
        if (failure != null || cancelled) {
            return;
        }
        if (!preparation.isDone() && (!freezeDuringPreparation
                || !preparation.awaitUntil(deadlineNanos))) {
            return;
        }
        if (delegate == null) {
            try {
                delegate = preparation.claim();
                validation.validate();
                if (!freezeDuringPreparation) {
                    return;
                }
            } catch (CompletionException failed) {
                failure = failed.getCause() == null ? failed : failed.getCause();
                return;
            } catch (IOException | RuntimeException failed) {
                discardAfterValidationFailure(failed);
                return;
            }
        }
        delegate.advance(deadlineNanos);
    }

    private void discardAfterValidationFailure(Throwable failed) {
        failure = failed;
        if (delegate == null) {
            return;
        }
        try {
            discard.discard(delegate);
        } catch (IOException discardFailure) {
            failure.addSuppressed(discardFailure);
        }
    }

    @Override
    public Optional<Throwable> failure() {
        return failure != null ? Optional.of(failure)
                : delegate == null ? Optional.empty() : delegate.failure();
    }

    @Override public OperationProgress progress() {
        return delegate == null ? preparationProgress.get()
                : delegate.progress();
    }

    @Override public Optional<RestoreApplyStatistics> restoreStatistics() {
        return delegate == null ? Optional.empty() : delegate.restoreStatistics();
    }

    @Override
    public MutationTerminalState terminalState() {
        if (cancelled) {
            return MutationTerminalState.CANCELLED;
        }
        if (failure != null) {
            return degradeOnFailure
                    ? MutationTerminalState.DEGRADED : MutationTerminalState.FAILED;
        }
        if (delegate == null || !delegate.isTerminal()) {
            throw new IllegalStateException("Prepared mutation is not terminal");
        }
        return delegate.terminalState();
    }

    @Override
    public boolean isTerminal() {
        return cancelled || failure != null || delegate != null && delegate.isTerminal();
    }

    @Override
    public boolean isSafeToRelease() {
        return cancelled || failure != null && !degradeOnFailure
                || delegate != null && delegate.isSafeToRelease();
    }

    @Override
    public MutationTerminalState unhandledFailureState() {
        return delegate == null
                ? (degradeOnFailure ? MutationTerminalState.DEGRADED
                        : MutationTerminalState.FAILED)
                : delegate.unhandledFailureState();
    }

    @Override
    public boolean cancel() throws IOException {
        if (degradeOnFailure || isTerminal()) {
            return false;
        }
        if (delegate != null) {
            return delegate.cancel();
        }
        if (!preparation.isDone() || preparation.isCompletedExceptionally()
                || preparation.isCancelled()) {
            return false;
        }
        T prepared = preparation.claim();
        discardPrepared(prepared);
        cancelled = true;
        return true;
    }

    @Override
    public void close() throws IOException {
        if (delegate != null) {
            delegate.close();
            return;
        }
        preparation.close();
        cancelled = true;
    }

    private void discardPrepared(T prepared) throws IOException {
        IOException failure = null;
        try {
            discard.discard(prepared);
        } catch (IOException failed) {
            failure = failed;
        }
        try {
            prepared.close();
        } catch (IOException failed) {
            if (failure == null) {
                failure = failed;
            } else {
                failure.addSuppressed(failed);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @FunctionalInterface
    public interface FrozenValidation {
        void validate() throws IOException;
    }

    @FunctionalInterface
    public interface PreparedDiscard<T> {
        void discard(T prepared) throws IOException;
    }
}
