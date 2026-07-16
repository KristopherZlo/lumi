package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.CapturedWorldState;
import io.github.lumi.domain.service.SavePublicationProgress;
import io.github.lumi.domain.service.SavePublisher;
import io.github.lumi.domain.service.SaveRequest;
import io.github.lumi.domain.service.SaveResult;
import io.github.lumi.minecraft.world.WorldStateCapture;
import io.github.lumi.minecraft.world.SavePreparation;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Copies visible state while frozen, then publishes it without holding the freeze. */
public final class SaveCaptureOperation implements DimensionMutation {
    private final SaveRequest request;
    private final SavePreparation preparation;
    private final WorldStateCapture capture;
    private final SavePublisher publisher;
    private final CapturedGenerationCompletion completion;
    private final Executor backgroundExecutor;
    private WorkingIndexSnapshot dirty;
    private SavePreparation.Session preparationSession;
    private WorldStateCapture.CaptureSession session;
    private CompletableFuture<SaveResult> background;
    private SaveOperationStatus status = SaveOperationStatus.PREPARING;
    private SaveResult result;
    private Throwable failure;
    private volatile OperationProgress publicationProgress =
            OperationProgress.indeterminate("Save: starting publication");

    public SaveCaptureOperation(
            SaveRequest request,
            WorkingIndexSnapshot dirty,
            WorldStateCapture capture,
            SavePublisher publisher,
            CapturedGenerationCompletion completion,
            Executor backgroundExecutor) {
        this(request, fixedPreparation(dirty), capture, publisher, completion, backgroundExecutor);
    }

    public SaveCaptureOperation(
            SaveRequest request,
            SavePreparation preparation,
            WorldStateCapture capture,
            SavePublisher publisher,
            CapturedGenerationCompletion completion,
            Executor backgroundExecutor) {
        this.request = Objects.requireNonNull(request, "request");
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.backgroundExecutor = Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
    }

    @Override
    public void advance(long deadlineNanos) throws IOException {
        if (status == SaveOperationStatus.PREPARING) {
            prepare(deadlineNanos);
        } else if (status == SaveOperationStatus.CAPTURING) {
            capture(deadlineNanos);
        } else if (status == SaveOperationStatus.WRITING && background.isDone()) {
            finishBackground();
        }
    }

    private void prepare(long deadlineNanos) throws IOException {
        if (preparationSession == null) {
            preparationSession = Objects.requireNonNull(
                    preparation.begin(), "preparation session");
        }
        if (!preparationSession.prepareUntil(deadlineNanos)) {
            return;
        }
        dirty = Objects.requireNonNull(preparationSession.finish(), "prepared generations");
        status = SaveOperationStatus.CAPTURING;
        capture(deadlineNanos);
    }

    private void capture(long deadlineNanos) throws IOException {
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
            SaveResult saved = publisher.save(request, captured, this::recordProgress);
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

    @Override
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    @Override
    public MutationTerminalState terminalState() {
        if (!isTerminal()) {
            throw new IllegalStateException("Save is not terminal");
        }
        return status == SaveOperationStatus.COMPLETE
                ? MutationTerminalState.SUCCEEDED : MutationTerminalState.FAILED;
    }

    @Override public boolean isTerminal() {
        return status == SaveOperationStatus.COMPLETE || status == SaveOperationStatus.FAILED;
    }

    @Override public boolean isSafeToRelease() {
        return status == SaveOperationStatus.WRITING || isTerminal();
    }

    @Override public MutationTerminalState unhandledFailureState() {
        return MutationTerminalState.FAILED;
    }

    @Override public OperationProgress progress() {
        if (status == SaveOperationStatus.CAPTURING && session != null
                && session.totalKeys() > 0) {
            return new OperationProgress(
                    "Save: capturing visible world",
                    session.completedKeys(),
                    session.totalKeys());
        }
        if (status == SaveOperationStatus.WRITING) {
            return publicationProgress;
        }
        return OperationProgress.indeterminate("Save: " + status.name().toLowerCase());
    }

    private void recordProgress(SavePublicationProgress progress) {
        publicationProgress = new OperationProgress(
                progress.phase(), progress.completed(), progress.total());
    }

    private static SavePreparation fixedPreparation(WorkingIndexSnapshot dirty) {
        WorkingIndexSnapshot fixed = Objects.requireNonNull(dirty, "dirty");
        return () -> new SavePreparation.Session() {
            @Override public boolean prepareUntil(long deadlineNanos) { return true; }
            @Override public WorkingIndexSnapshot finish() { return fixed; }
        };
    }
}
