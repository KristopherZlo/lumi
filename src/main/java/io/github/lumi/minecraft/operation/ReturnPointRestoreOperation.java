package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.service.SaveResult;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Keeps one freeze across a hidden return-point Save and the following full Restore. */
public final class ReturnPointRestoreOperation implements DimensionMutation {
    private final SaveCaptureOperation returnPointSave;
    private final RestorePreparation restorePreparation;
    private Phase phase = Phase.SAVING_RETURN_POINT;
    private PreparedMutationOwnership<DimensionMutation> preparation;
    private DimensionMutation restore;
    private SaveResult returnPoint;
    private Throwable failure;
    private boolean cancelled;

    public ReturnPointRestoreOperation(
            SaveCaptureOperation returnPointSave,
            RestorePreparation restorePreparation) {
        this.returnPointSave = Objects.requireNonNull(returnPointSave, "returnPointSave");
        this.restorePreparation = Objects.requireNonNull(restorePreparation, "restorePreparation");
    }

    @Override
    public void advance(long deadlineNanos) throws IOException {
        if (failure != null || cancelled) {
            return;
        }
        switch (phase) {
            case SAVING_RETURN_POINT -> advanceReturnPoint(deadlineNanos);
            case PREPARING_RESTORE -> advancePreparation(deadlineNanos);
            case RESTORING -> restore.advance(deadlineNanos);
        }
    }

    private void advanceReturnPoint(long deadlineNanos) throws IOException {
        returnPointSave.advance(deadlineNanos);
        if (!returnPointSave.isTerminal()) {
            return;
        }
        if (returnPointSave.failure().isPresent()) {
            failure = returnPointSave.failure().orElseThrow();
            return;
        }
        returnPoint = returnPointSave.result().orElseThrow(
                () -> new IllegalStateException("Completed return-point Save has no result"));
        try {
            CompletableFuture<? extends DimensionMutation> prepared = Objects.requireNonNull(
                    restorePreparation.prepare(returnPoint), "restore preparation");
            preparation = new PreparedMutationOwnership<>(
                    prepared.thenApply(operation -> operation), DimensionMutation::close);
            phase = Phase.PREPARING_RESTORE;
        } catch (RuntimeException failed) {
            failure = failed;
        }
    }

    private void advancePreparation(long deadlineNanos) throws IOException {
        if (!preparation.isDone()) {
            return;
        }
        try {
            restore = preparation.claim();
            phase = Phase.RESTORING;
            restore.advance(deadlineNanos);
        } catch (CompletionException failed) {
            failure = failed.getCause() == null ? failed : failed.getCause();
        }
    }

    public Optional<SaveResult> returnPoint() {
        return Optional.ofNullable(returnPoint);
    }

    @Override
    public Optional<Throwable> failure() {
        return failure != null ? Optional.of(failure)
                : restore == null ? Optional.empty() : restore.failure();
    }

    @Override
    public MutationTerminalState terminalState() {
        if (cancelled) {
            return MutationTerminalState.CANCELLED;
        }
        if (failure != null) {
            return MutationTerminalState.FAILED;
        }
        if (phase != Phase.RESTORING || !restore.isTerminal()) {
            throw new IllegalStateException("Return-point Restore is not terminal");
        }
        return restore.terminalState();
    }

    @Override
    public boolean isTerminal() {
        return cancelled || failure != null
                || phase == Phase.RESTORING && restore.isTerminal();
    }

    @Override
    public boolean isSafeToRelease() {
        return cancelled || failure != null
                || phase == Phase.RESTORING && restore.isSafeToRelease();
    }

    @Override public OperationProgress progress() {
        return switch (phase) {
            case SAVING_RETURN_POINT -> returnPointSave.progress();
            case PREPARING_RESTORE -> OperationProgress.indeterminate("Preparing Restore");
            case RESTORING -> restore.progress();
        };
    }

    @Override
    public MutationTerminalState unhandledFailureState() {
        return phase == Phase.RESTORING
                ? restore.unhandledFailureState() : MutationTerminalState.FAILED;
    }

    @Override
    public boolean cancel() throws IOException {
        boolean accepted = switch (phase) {
            case SAVING_RETURN_POINT -> returnPointSave.cancel();
            case PREPARING_RESTORE -> cancelPreparedRestore();
            case RESTORING -> restore.cancel();
        };
        cancelled = accepted;
        return accepted;
    }

    @Override
    public void close() throws IOException {
        returnPointSave.close();
        if (restore != null) {
            restore.close();
        } else if (preparation != null) {
            preparation.close();
        }
        cancelled = true;
    }

    private boolean cancelPreparedRestore() throws IOException {
        if (!preparation.isDone() || preparation.isCompletedExceptionally()
                || preparation.isCancelled()) {
            return false;
        }
        restore = preparation.claim();
        phase = Phase.RESTORING;
        return restore.cancel();
    }

    @FunctionalInterface
    public interface RestorePreparation {
        CompletableFuture<? extends DimensionMutation> prepare(SaveResult returnPoint);
    }

    private enum Phase {
        SAVING_RETURN_POINT,
        PREPARING_RESTORE,
        RESTORING
    }
}
