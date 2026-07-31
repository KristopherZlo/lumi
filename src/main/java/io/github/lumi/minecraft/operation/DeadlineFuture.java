package io.github.lumi.minecraft.operation;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Uses the caller's remaining tick budget instead of polling background work next tick. */
public final class DeadlineFuture {
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
}
