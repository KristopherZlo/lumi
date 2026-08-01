package io.github.lumi.minecraft.runtime;

import io.github.lumi.storage.repository.GarbageCollectionResult;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Places infrequent storage collection on the bounded background executor. */
final class GarbageCollectionScheduler {
    static final long STARTUP_COMPACTION_DELAY_TICKS = 200;
    static final long INITIAL_DELAY_TICKS = 6_000;
    static final long RETRY_TICKS = 1_200;
    private static final long INTERVAL_TICKS = 72_000;
    private final Executor background;
    private final Callable<Integer> startupCompaction;
    private final Consumer<Integer> compactionSuccess;
    private final Callable<GarbageCollectionResult> collection;
    private final Consumer<GarbageCollectionResult> success;
    private final Consumer<Throwable> failure;
    private final AtomicBoolean running = new AtomicBoolean();
    private final long initialCollectionTick;
    private volatile boolean startupComplete;
    private long nextTick;

    GarbageCollectionScheduler(
            long openedAt,
            Executor background,
            Callable<Integer> startupCompaction,
            Consumer<Integer> compactionSuccess,
            Callable<GarbageCollectionResult> collection,
            Consumer<GarbageCollectionResult> success,
            Consumer<Throwable> failure) {
        nextTick = openedAt + STARTUP_COMPACTION_DELAY_TICKS;
        initialCollectionTick = openedAt + INITIAL_DELAY_TICKS;
        this.background = Objects.requireNonNull(background, "background");
        this.startupCompaction = Objects.requireNonNull(
                startupCompaction, "startupCompaction");
        this.compactionSuccess = Objects.requireNonNull(
                compactionSuccess, "compactionSuccess");
        this.collection = Objects.requireNonNull(collection, "collection");
        this.success = Objects.requireNonNull(success, "success");
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    void tick(long gameTick, boolean busy) {
        if (running.get() || gameTick < nextTick) {
            return;
        }
        boolean compact = !startupComplete;
        if (busy) {
            nextTick = gameTick + RETRY_TICKS;
            return;
        }
        nextTick = compact
                ? Math.max(initialCollectionTick, gameTick + RETRY_TICKS)
                : gameTick + INTERVAL_TICKS;
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            background.execute(compact ? this::compact : this::collect);
        } catch (RuntimeException rejected) {
            running.set(false);
            failure.accept(rejected);
        }
    }

    boolean running() {
        return running.get();
    }

    private void compact() {
        run(startupCompaction, compactionSuccess,
                () -> startupComplete = true);
    }

    private void collect() {
        run(collection, success, () -> { });
    }

    private <T> void run(
            Callable<T> task, Consumer<T> succeeded, Runnable completion) {
        try {
            succeeded.accept(task.call());
        } catch (Exception failed) {
            failure.accept(failed);
        } finally {
            completion.run();
            running.set(false);
        }
    }
}
