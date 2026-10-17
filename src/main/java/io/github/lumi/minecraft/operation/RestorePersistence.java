package io.github.lumi.minecraft.operation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** One background persistence step at a time, owned until its result is consumed. */
final class RestorePersistence {
    private final Executor background;
    private CompletableFuture<Void> pending;
    private Step step;

    RestorePersistence(Executor background) {
        this.background = Objects.requireNonNull(background, "background");
    }

    boolean await(Step requested, Write write) throws IOException {
        if (pending == null) {
            step = requested;
            pending = CompletableFuture.runAsync(() -> {
                try {
                    write.run();
                } catch (IOException failed) {
                    throw new UncheckedIOException(failed);
                }
            }, background);
        } else if (step != requested) {
            throw new IllegalStateException("Restore persistence step is still owned: " + step);
        }
        if (!pending.isDone()) {
            return false;
        }
        finish();
        return true;
    }

    boolean isPending() {
        return pending != null;
    }

    /** Shutdown must join the writer before closing the immutable plan it uses. */
    void drain() throws IOException {
        if (pending != null) {
            finish();
        }
    }

    private void finish() throws IOException {
        try {
            pending.join();
        } catch (CompletionException failed) {
            if (failed.getCause() instanceof UncheckedIOException io) {
                throw io.getCause();
            }
            throw failed;
        } finally {
            pending = null;
            step = null;
        }
    }

    enum Step { CREATE, APPLY, RETURN, DEGRADE, PUBLISH, PUBLISH_RETURN, CLEAR }

    @FunctionalInterface
    interface Write {
        void run() throws IOException;
    }
}
