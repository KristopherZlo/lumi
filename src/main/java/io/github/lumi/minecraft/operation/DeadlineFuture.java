package io.github.lumi.minecraft.operation;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** Uses the caller's remaining tick budget instead of polling background work next tick. */
public final class DeadlineFuture {
    private static final long POLL_NANOS = 100_000;

    private DeadlineFuture() { }

    public static boolean await(CompletableFuture<?> future, long deadlineNanos)
            throws IOException {
        Objects.requireNonNull(future, "future");
        if (future.isDone()) {
            return true;
        }
        if (deadlineNanos == Long.MAX_VALUE) {
            return false;
        }
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return false;
        }
        try {
            future.get(remaining, TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException timedOut) {
            return false;
        } catch (ExecutionException | CancellationException completed) {
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while awaiting background work", interrupted);
        }
    }

    /** Waits for background-owned state without exceeding the current tick budget. */
    public static boolean await(BooleanSupplier ready, long deadlineNanos) {
        Objects.requireNonNull(ready, "ready");
        if (ready.getAsBoolean()) {
            return true;
        }
        if (deadlineNanos == Long.MAX_VALUE) {
            return false;
        }
        long remaining;
        while ((remaining = deadlineNanos - System.nanoTime()) > 0) {
            LockSupport.parkNanos(Math.min(remaining, POLL_NANOS));
            if (ready.getAsBoolean()) {
                return true;
            }
        }
        return ready.getAsBoolean();
    }
}
