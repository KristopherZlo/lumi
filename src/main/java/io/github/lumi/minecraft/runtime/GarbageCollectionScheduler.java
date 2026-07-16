package io.github.lumi.minecraft.runtime;

import io.github.lumi.storage.repository.GarbageCollectionResult;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Places infrequent storage collection on the bounded background executor. */
final class GarbageCollectionScheduler {
    static final long INITIAL_DELAY_TICKS = 6_000;
    static final long RETRY_TICKS = 1_200;
    private static final long INTERVAL_TICKS = 72_000;
    private final Executor background;
    private final Callable<GarbageCollectionResult> collection;
    private final Consumer<GarbageCollectionResult> success;
    private final Consumer<Throwable> failure;
    private final AtomicBoolean running = new AtomicBoolean();
    private long nextTick;

    GarbageCollectionScheduler(
            long openedAt,
            Executor background,
            Callable<GarbageCollectionResult> collection,
            Consumer<GarbageCollectionResult> success,
            Consumer<Throwable> failure) {
        nextTick = openedAt + INITIAL_DELAY_TICKS;
        this.background = Objects.requireNonNull(background, "background");
        this.collection = Objects.requireNonNull(collection, "collection");
        this.success = Objects.requireNonNull(success, "success");
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    void tick(long gameTick, boolean busy) {
        if (running.get() || gameTick < nextTick) {
            return;
        }
        nextTick = gameTick + (busy ? RETRY_TICKS : INTERVAL_TICKS);
        if (busy || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            background.execute(this::collect);
        } catch (RuntimeException rejected) {
            running.set(false);
            failure.accept(rejected);
        }
    }

    private void collect() {
        try {
            success.accept(collection.call());
        } catch (Exception failed) {
            failure.accept(failed);
        } finally {
            running.set(false);
        }
    }
}
