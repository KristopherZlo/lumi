package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class DeadlineFutureTest {
    @Test
    void keepsUnboundedDeadlinesNonBlockingAndUsesFiniteBudget() throws Exception {
        CompletableFuture<Void> pending = new CompletableFuture<>();

        assertFalse(DeadlineFuture.await(pending, Long.MAX_VALUE));
        pending.complete(null);
        assertTrue(DeadlineFuture.await(pending, Long.MAX_VALUE));

        AtomicBoolean ready = new AtomicBoolean();
        Thread completion = Thread.startVirtualThread(() -> {
            LockSupport.parkNanos(1_000_000);
            ready.set(true);
        });
        assertTrue(DeadlineFuture.await(
                ready::get, System.nanoTime() + 1_000_000_000));
        completion.join();
        assertFalse(DeadlineFuture.await(() -> false, Long.MAX_VALUE));
    }
}
