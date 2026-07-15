package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.CapturedWorldState;
import io.github.lumi.domain.service.SavePublisher;
import io.github.lumi.domain.service.SaveRequest;
import io.github.lumi.domain.service.SaveResult;
import io.github.lumi.minecraft.world.WorldStateCapture;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Copies visible state while frozen, then publishes it without holding the freeze. */
public final class SaveCaptureOperation implements DimensionMutation {
    private final SaveRequest request;
    private final WorkingIndexSnapshot dirty;
    private final WorldStateCapture capture;
    private final SavePublisher publisher;
    private final CapturedGenerationCompletion completion;
    private final Executor backgroundExecutor;
    private WorldStateCapture.CaptureSession session;
    private CompletableFuture<SaveResult> background;
    private SaveOperationStatus status = SaveOperationStatus.CAPTURING;
    private SaveResult result;
    private Throwable failure;

    public SaveCaptureOperation(
            SaveRequest request,
            WorkingIndexSnapshot dirty,
            WorldStateCapture capture,
            SavePublisher publisher,
            CapturedGenerationCompletion completion,
            Executor backgroundExecutor) {
        this.request = Objects.requireNonNull(request, "request");
        this.dirty = Objects.requireNonNull(dirty, "dirty");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.backgroundExecutor = Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
    }

    @Override
    public void advance(long deadlineNanos) {
        if (status == SaveOperationStatus.CAPTURING) {
            capture(deadlineNanos);
        } else if (status == SaveOperationStatus.WRITING && background.isDone()) {
            finishBackground();
        }
    }

    private void capture(long deadlineNanos) {
        if (session == null) {
            session = Objects.requireNonNull(capture.begin(dirty), "capture session");
        }
        if (!session.captureUntil(deadlineNanos)) {
            return;
        }
        CapturedWorldState captured = Objects.requireNonNull(session.finish(), "captured world state");
        if (!captured.generations().equals(dirty)) {
            throw new IllegalStateException("Capture generations changed during frozen capture");
        }
        status = SaveOperationStatus.WRITING;
        background = CompletableFuture.supplyAsync(() -> publish(captured), backgroundExecutor);
    }

    private SaveResult publish(CapturedWorldState captured) {
        try {
            SaveResult saved = publisher.save(request, captured);
            if (!saved.capturedGenerations().equals(dirty)) {
                throw new IOException("Save result generations differ from capture");
            }
            completion.clear(saved.capturedGenerations());
            return saved;
        } catch (IOException failed) {
            throw new CompletionException(failed);
        }
    }

    private void finishBackground() {
        try {
            result = background.join();
            status = SaveOperationStatus.COMPLETE;
        } catch (CompletionException failed) {
            failure = failed.getCause();
            status = SaveOperationStatus.FAILED;
        }
    }

    public SaveOperationStatus status() {
        return status;
    }

    public Optional<SaveResult> result() {
        return Optional.ofNullable(result);
    }

    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    @Override public boolean isTerminal() {
        return status == SaveOperationStatus.COMPLETE || status == SaveOperationStatus.FAILED;
    }

    @Override public boolean isSafeToRelease() {
        return status != SaveOperationStatus.CAPTURING;
    }
}
