package io.github.lumi.minecraft.world;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Delays ordinary index publication without occupying a persistence worker. */
final class IndexWriteScheduler {
    private final Executor worker;
    private final Executor delayed;
    private Task pending;
    private boolean stopped;

    IndexWriteScheduler(Executor worker, long delayMillis) {
        this(worker, delayMillis == 0 ? Runnable::run
                : CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS));
        if (delayMillis < 0) throw new IllegalArgumentException("Negative coalescing delay");
    }

    IndexWriteScheduler(Executor worker, Executor delayed) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.delayed = Objects.requireNonNull(delayed, "delayed");
    }

    void schedule(Runnable write, Consumer<RuntimeException> rejected, boolean urgent) {
        Task task = new Task(write, rejected);
        boolean immediate;
        synchronized (this) {
            pending = task;
            immediate = urgent || stopped;
        }
        if (immediate) task.run();
        else delayed.execute(task);
    }

    void flush() {
        Task task;
        synchronized (this) { task = pending; }
        if (task != null) task.run();
    }

    /** Owns pending timer dispatch before the server closes its worker pool. */
    void stop() {
        Task task;
        synchronized (this) {
            stopped = true;
            task = pending;
        }
        if (task != null) {
            task.run();
            task.dispatched.join();
        }
    }

    private final class Task implements Runnable {
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final CompletableFuture<Void> dispatched = new CompletableFuture<>();
        private final Runnable write;
        private final Consumer<RuntimeException> rejected;

        private Task(Runnable write, Consumer<RuntimeException> rejected) {
            this.write = write;
            this.rejected = rejected;
        }

        @Override public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            boolean inline;
            synchronized (IndexWriteScheduler.this) {
                inline = stopped;
            }
            try {
                if (inline) write.run();
                else worker.execute(write);
            }
            catch (RuntimeException failure) { rejected.accept(failure); }
            finally {
                synchronized (IndexWriteScheduler.this) {
                    if (pending == this) pending = null;
                }
                dispatched.complete(null);
            }
        }
    }
}
