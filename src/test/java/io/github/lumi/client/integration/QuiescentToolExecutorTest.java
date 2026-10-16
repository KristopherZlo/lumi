package io.github.lumi.client.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class QuiescentToolExecutorTest {
    @Test void cancelledFutureIsNotProofThatItsCalculationStopped() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var executor = new QuiescentToolExecutor(Executors.defaultThreadFactory());
        try {
            var calculation = executor.submit(() -> {
                started.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(calculation.cancel(true));
            assertTrue(calculation.isDone());
            assertFalse(executor.isQuiescent());
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue(executor.isQuiescent());
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        assertTrue(executor.isQuiescent());
    }

    @Test void shutdownDoesNotLeaveDiscardedQueueEntriesCountedAsActive() throws Exception {
        var started = new CountDownLatch(1);
        var executor = new QuiescentToolExecutor(Executors.defaultThreadFactory());
        try {
            executor.submit(() -> {
                started.countDown();
                new CountDownLatch(1).await();
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var queued = executor.submit(() -> { });
            assertTrue(queued.cancel(false));
            assertFalse(executor.isQuiescent());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue(executor.isQuiescent());
    }
}
