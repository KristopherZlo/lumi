package io.github.lumi.minecraft.operation;

import io.github.lumi.LumiMod;
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
    private final DeferredDimensionMutation.Activation activation;
    private final SavePreparation preparation;
    private final WorldStateCapture capture;
    private final SavePublisher publisher;
    private final CapturedGenerationCompletion completion;
    private final Executor backgroundExecutor;
    private WorkingIndexSnapshot dirty;
    private WorkingIndexSnapshot previewDirty = WorkingIndexSnapshot.empty();
    private SavePreparation.Session preparationSession;
    private WorldStateCapture.CaptureSession session;
    private CompletableFuture<SaveResult> background;
    private SaveOperationStatus status = SaveOperationStatus.PREPARING;
    private SaveResult result;
    private Throwable failure;
    private boolean activated;
    private boolean resourcesClosed;
    private long startedNanos;
    private volatile String loggedPublicationPhase = "";
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
        this(request, () -> { }, preparation, capture, publisher, completion,
                backgroundExecutor);
    }

    public SaveCaptureOperation(
            SaveRequest request,
            DeferredDimensionMutation.Activation activation,
            SavePreparation preparation,
            WorldStateCapture capture,
            SavePublisher publisher,
            CapturedGenerationCompletion completion,
            Executor backgroundExecutor) {
        this.request = Objects.requireNonNull(request, "request");
        this.activation = Objects.requireNonNull(activation, "activation");
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.backgroundExecutor = Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
    }

    @Override
    public void advance(long deadlineNanos) throws IOException {
        startIfNeeded();
        if (!activate()) {
            return;
        }
        if (status == SaveOperationStatus.PREPARING) {
            prepare(deadlineNanos);
        } else if (status == SaveOperationStatus.CAPTURING) {
            capture(deadlineNanos);
        }
        if (status == SaveOperationStatus.WRITING && background.isDone()) {
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
        previewDirty = Objects.requireNonNull(
                preparationSession.previewGenerations(), "preview generations");
        LumiMod.LOGGER.info(
                "Lumi Save prepared {} dirty keys in {} ms",
                dirty.generations().size(), elapsedMillis());
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
        closeSessions();
        LumiMod.LOGGER.info(
                "Lumi Save captured {} sections and {} entity chunks in {} ms",
                captured.sections().size(), captured.entities().size(), elapsedMillis());
        status = SaveOperationStatus.WRITING;
        background = CompletableFuture.supplyAsync(() -> publish(captured), backgroundExecutor);
    }

    private SaveResult publish(CapturedWorldState captured) {
        try {
            SaveResult saved = publisher.save(
                    request, captured, this::recordProgress, completion);
            if (!saved.capturedGenerations().equals(dirty)) {
                throw new IOException("Save result generations differ from capture");
            }
            return saved;
        } catch (IOException failed) {
            throw new CompletionException(failed);
        }
    }

    private void finishBackground() {
        try {
            result = background.join();
            status = SaveOperationStatus.COMPLETE;
            LumiMod.LOGGER.info(
                    "Lumi Save published commit {} in {} ms",
                    result.commitId(), elapsedMillis());
        } catch (CompletionException failed) {
            failure = failed.getCause();
            status = SaveOperationStatus.FAILED;
            LumiMod.LOGGER.error(
                    "Lumi Save failed after " + elapsedMillis() + " ms", failure);
        }
    }

    public SaveOperationStatus status() {
        return status;
    }

    public Optional<SaveResult> result() {
        return Optional.ofNullable(result);
    }

    private boolean activate() {
        if (activated) {
            return true;
        }
        try {
            activation.validate();
            activated = true;
            return true;
        } catch (IOException | RuntimeException rejected) {
            failure = rejected;
            status = SaveOperationStatus.FAILED;
            LumiMod.LOGGER.warn(
                    "Lumi Save rejected at activation after {} ms: {}",
                    elapsedMillis(), rejected.getMessage());
            return false;
        }
    }

    public WorkingIndexSnapshot previewGenerations() {
        return previewDirty;
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
        return switch (status) {
            case COMPLETE -> MutationTerminalState.SUCCEEDED;
            case CANCELLED -> MutationTerminalState.CANCELLED;
            case FAILED -> MutationTerminalState.FAILED;
            default -> throw new IllegalStateException("Save is not terminal");
        };
    }

    @Override public boolean isTerminal() {
        return status == SaveOperationStatus.COMPLETE
                || status == SaveOperationStatus.CANCELLED
                || status == SaveOperationStatus.FAILED;
    }

    @Override public boolean isSafeToRelease() {
        return status == SaveOperationStatus.WRITING || isTerminal();
    }

    @Override public MutationTerminalState unhandledFailureState() {
        return MutationTerminalState.FAILED;
    }

    @Override public OperationProgress progress() {
        if (status == SaveOperationStatus.PREPARING && preparationSession != null) {
            return preparationSession.progress();
        }
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
        if (!progress.phase().equals(loggedPublicationPhase)) {
            loggedPublicationPhase = progress.phase();
            LumiMod.LOGGER.info(
                    "Lumi Save phase '{}' started at {} ms ({}/{})",
                    progress.phase(), elapsedMillis(),
                    progress.completed(), progress.total());
        }
    }

    @Override
    public boolean cancel() throws IOException {
        if (status != SaveOperationStatus.PREPARING
                && status != SaveOperationStatus.CAPTURING) {
            return false;
        }
        closeSessions();
        status = SaveOperationStatus.CANCELLED;
        LumiMod.LOGGER.warn("Lumi Save cancelled after {} ms", elapsedMillis());
        return true;
    }

    @Override
    public void close() throws IOException {
        if (!isTerminal() && status != SaveOperationStatus.WRITING) {
            cancel();
        } else {
            closeSessions();
        }
        if (background != null && !background.isDone()) {
            background.cancel(true);
        }
    }

    private void closeSessions() throws IOException {
        if (resourcesClosed) {
            return;
        }
        resourcesClosed = true;
        IOException failure = null;
        if (session != null) {
            try {
                session.close();
            } catch (IOException failed) {
                failure = failed;
            }
        }
        if (preparationSession != null) {
            try {
                preparationSession.close();
            } catch (IOException failed) {
                if (failure == null) {
                    failure = failed;
                } else {
                    failure.addSuppressed(failed);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static SavePreparation fixedPreparation(WorkingIndexSnapshot dirty) {
        WorkingIndexSnapshot fixed = Objects.requireNonNull(dirty, "dirty");
        return () -> new SavePreparation.Session() {
            @Override public boolean prepareUntil(long deadlineNanos) { return true; }
            @Override public WorkingIndexSnapshot finish() { return fixed; }
            @Override public WorkingIndexSnapshot previewGenerations() { return fixed; }
        };
    }

    private void startIfNeeded() {
        if (startedNanos != 0) {
            return;
        }
        startedNanos = System.nanoTime();
        LumiMod.LOGGER.info(
                "Lumi Save started: kind={}, branch={}, workspace={}, zone={}",
                request.kind(), request.expectedRef().name(),
                request.workspaceId(), request.zoneId().orElse(null));
    }

    private long elapsedMillis() {
        return startedNanos == 0
                ? 0 : java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startedNanos);
    }
}
