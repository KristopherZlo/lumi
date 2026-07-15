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
    private CompletableFuture<? extends DimensionMutation> preparation;
    private DimensionMutation restore;
    private SaveResult returnPoint;
    private Throwable failure;

    public ReturnPointRestoreOperation(
            SaveCaptureOperation returnPointSave,
            RestorePreparation restorePreparation) {
        this.returnPointSave = Objects.requireNonNull(returnPointSave, "returnPointSave");
        this.restorePreparation = Objects.requireNonNull(restorePreparation, "restorePreparation");
    }

    @Override
    public void advance(long deadlineNanos) throws IOException {
        if (failure != null) {
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
            preparation = Objects.requireNonNull(
                    restorePreparation.prepare(returnPoint), "restore preparation");
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
            restore = Objects.requireNonNull(preparation.join(), "prepared restore");
            phase = Phase.RESTORING;
            restore.advance(deadlineNanos);
        } catch (CompletionException failed) {
            failure = failed.getCause() == null ? failed : failed.getCause();
        }
    }

    public Optional<SaveResult> returnPoint() {
        return Optional.ofNullable(returnPoint);
    }

    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    @Override
    public boolean isTerminal() {
        return failure != null || phase == Phase.RESTORING && restore.isTerminal();
    }

    @Override
    public boolean isSafeToRelease() {
        return failure != null || phase == Phase.RESTORING && restore.isSafeToRelease();
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
