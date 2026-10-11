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
        synchronized (this) { pending = task; }
        if (urgent) task.run();
        else delayed.execute(task);
    }

    void flush() {
        Task task;
        synchronized (this) { task = pending; }
        if (task != null) task.run();
    }

    private final class Task implements Runnable {
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final Runnable write;
        private final Consumer<RuntimeException> rejected;

        private Task(Runnable write, Consumer<RuntimeException> rejected) {
            this.write = write;
            this.rejected = rejected;
        }

        @Override public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            synchronized (IndexWriteScheduler.this) {
                if (pending == this) pending = null;
            }
            try { worker.execute(write); }
            catch (RuntimeException failure) { rejected.accept(failure); }
        }
    }
}
