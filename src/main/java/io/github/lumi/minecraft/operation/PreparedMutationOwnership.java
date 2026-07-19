package io.github.lumi.minecraft.operation;

import io.github.lumi.LumiMod;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Transfers one asynchronously prepared mutation or closes it after its owner shuts down. */
final class PreparedMutationOwnership<T extends DimensionMutation> {
    private final CompletableFuture<T> preparation;
    private final Cleanup<T> cleanup;
    private boolean claimed;
    private boolean closed;

    PreparedMutationOwnership(
            CompletableFuture<T> preparation,
            Cleanup<T> cleanup) {
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        preparation.whenComplete(this::cleanupAfterClose);
    }

    boolean isDone() {
        return preparation.isDone();
    }

    boolean isCompletedExceptionally() {
        return preparation.isCompletedExceptionally();
    }

    boolean isCancelled() {
        return preparation.isCancelled();
    }

    synchronized T claim() {
        if (closed || claimed || !preparation.isDone()) {
            throw new IllegalStateException("Prepared mutation is not available");
        }
        T prepared = Objects.requireNonNull(preparation.join(), "prepared mutation");
        claimed = true;
        return prepared;
    }

    void close() throws IOException {
        T prepared = null;
        synchronized (this) {
            if (closed || claimed) {
                return;
            }
            closed = true;
            if (completedSuccessfully()) {
                prepared = Objects.requireNonNull(
                        preparation.join(), "prepared mutation");
                claimed = true;
            }
        }
        if (prepared != null) {
            cleanup.cleanup(prepared);
        }
    }

    private void cleanupAfterClose(T prepared, Throwable failed) {
        if (failed != null || !claimForCleanup()) {
            return;
        }
        try {
            cleanup.cleanup(Objects.requireNonNull(prepared, "prepared mutation"));
        } catch (IOException | RuntimeException cleanupFailure) {
            LumiMod.LOGGER.error(
                    "Failed to close a Lumi mutation prepared after its owner shut down",
                    cleanupFailure);
        }
    }

    private synchronized boolean claimForCleanup() {
        if (!closed || claimed) {
            return false;
        }
        claimed = true;
        return true;
    }

    private boolean completedSuccessfully() {
        return preparation.isDone()
                && !preparation.isCompletedExceptionally()
                && !preparation.isCancelled();
    }

    @FunctionalInterface
    interface Cleanup<T> {
        void cleanup(T prepared) throws IOException;
    }
}
