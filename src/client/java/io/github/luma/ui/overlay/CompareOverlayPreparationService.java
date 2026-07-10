package io.github.luma.ui.overlay;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.DiffBlockEntry;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;

/**
 * Keeps heavy compare overlay geometry preparation off the client thread.
 */
public final class CompareOverlayPreparationService {

    private static final CompareOverlayPreparationService INSTANCE = new CompareOverlayPreparationService();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "lumi-overlay-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong revision = new AtomicLong();
    private volatile Future<?> pendingTask;
    private volatile PreparationKey pendingKey;

    private CompareOverlayPreparationService() {
    }

    public static CompareOverlayPreparationService getInstance() {
        return INSTANCE;
    }

    public void prepareAndShow(
            String projectName,
            String leftVersionId,
            String rightVersionId,
            List<DiffBlockEntry> changedBlocks,
            boolean debugEnabled
    ) {
        PreparationKey key = new PreparationKey(
                projectName,
                leftVersionId,
                rightVersionId,
                changedBlocks == null ? 0 : changedBlocks.size(),
                changedBlocks == null ? 0 : changedBlocks.hashCode(),
                debugEnabled
        );
        if (key.equals(this.pendingKey)) {
            return;
        }
        this.pendingKey = key;
        long requestRevision = this.revision.incrementAndGet();
        this.cancelTask(this.pendingTask);
        this.pendingTask = this.executor.submit(() -> {
            CompareOverlayRenderer.PreparedOverlay prepared = null;
            Throwable failure = null;
            try {
                prepared = this.prepare(projectName, leftVersionId, rightVersionId, changedBlocks, debugEnabled);
            } catch (Throwable throwable) {
                failure = throwable;
            }
            this.apply(requestRevision, prepared, failure);
        });
    }

    public void cancelPending() {
        this.revision.incrementAndGet();
        this.pendingKey = null;
        this.cancelTask(this.pendingTask);
    }

    private CompareOverlayRenderer.PreparedOverlay prepare(
            String projectName,
            String leftVersionId,
            String rightVersionId,
            List<DiffBlockEntry> changedBlocks,
            boolean debugEnabled
    ) {
        try {
            return CompareOverlayRenderer.prepare(
                    projectName,
                    leftVersionId,
                    rightVersionId,
                    changedBlocks,
                    debugEnabled,
                    true
            );
        } catch (RuntimeException exception) {
            throw new CompletionException(exception);
        }
    }

    private void apply(
            long requestRevision,
            CompareOverlayRenderer.PreparedOverlay prepared,
            Throwable failure
    ) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            if (requestRevision == this.revision.get()) {
                this.pendingKey = null;
            }
            CompareOverlayRenderer.discard(prepared);
            return;
        }
        client.execute(() -> this.applyOnClientThread(requestRevision, prepared, failure));
    }

    private void applyOnClientThread(
            long requestRevision,
            CompareOverlayRenderer.PreparedOverlay prepared,
            Throwable failure
    ) {
        if (requestRevision != this.revision.get()) {
            CompareOverlayRenderer.discard(prepared);
            return;
        }
        this.pendingKey = null;
        if (failure != null) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
            LumaMod.LOGGER.warn("Failed to prepare compare overlay in the background", cause);
            return;
        }
        CompareOverlayRenderer.activate(prepared);
    }

    private void cancelTask(Future<?> task) {
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
    }

    private record PreparationKey(
            String projectName,
            String leftVersionId,
            String rightVersionId,
            int changedBlockCount,
            int changedBlocksFingerprint,
            boolean debugEnabled
    ) {
    }
}
