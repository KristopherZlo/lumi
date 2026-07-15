package io.github.lumi.minecraft.operation;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Owns an operation while it prepares off-thread, then validates and delegates under freeze. */
public final class BackgroundPreparedMutation<T extends DimensionMutation>
        implements DimensionMutation {
    private final CompletableFuture<T> preparation;
    private final FrozenValidation validation;
    private final PreparedDiscard<T> discard;
    private final boolean freezeDuringPreparation;
    private T delegate;
    private Throwable failure;

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
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.validation = Objects.requireNonNull(validation, "validation");
        this.discard = Objects.requireNonNull(discard, "discard");
        this.freezeDuringPreparation = freezeDuringPreparation;
    }

    @Override
    public boolean requiresFreeze() {
        return freezeDuringPreparation || delegate != null || (preparation.isDone()
                && !preparation.isCompletedExceptionally() && !preparation.isCancelled());
    }

    @Override
    public void advance(long deadlineNanos) throws IOException {
        if (failure != null || !preparation.isDone()) {
            return;
        }
        if (delegate == null) {
            try {
                delegate = Objects.requireNonNull(preparation.join(), "prepared mutation");
                validation.validate();
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
        return Optional.ofNullable(failure);
    }

    @Override
    public MutationTerminalState terminalState() {
        if (failure != null) {
            return MutationTerminalState.FAILED;
        }
        if (delegate == null || !delegate.isTerminal()) {
            throw new IllegalStateException("Prepared mutation is not terminal");
        }
        return delegate.terminalState();
    }

    @Override
    public boolean isTerminal() {
        return failure != null || delegate != null && delegate.isTerminal();
    }

    @Override
    public boolean isSafeToRelease() {
        return failure != null || delegate != null && delegate.isSafeToRelease();
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
