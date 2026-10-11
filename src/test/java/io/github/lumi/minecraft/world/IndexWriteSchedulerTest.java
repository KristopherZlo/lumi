package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IndexWriteSchedulerTest {
    @Test
    void shutdownConsumesPendingTimerAndLaterWritesWithoutWorkerSubmission() {
        var timers = new ArrayDeque<Runnable>();
        var writes = new AtomicInteger();
        var scheduler = new IndexWriteScheduler(command -> fail("Worker pool must not receive shutdown writes"),
                timers::add);
        scheduler.schedule(writes::incrementAndGet, rejected -> fail(rejected), false);
        scheduler.stop();
        assertEquals(1, writes.get());
        timers.remove().run();
        scheduler.schedule(writes::incrementAndGet, rejected -> fail(rejected), false);
        assertEquals(2, writes.get());
        assertTrue(timers.isEmpty());
    }

    @Test
    void shutdownWaitsForClaimedDispatchBeforeTheWorkerPoolCanClose() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var stopped = new java.util.concurrent.CountDownLatch(1);
        var workers = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
        var scheduler = new IndexWriteScheduler(command -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException failure) { throw new IllegalStateException(failure); }
            workers.add(command);
        }, Runnable::run);
        var producer = new Thread(() -> scheduler.schedule(() -> { }, rejected -> fail(rejected), false));
        var closer = new Thread(() -> { scheduler.stop(); stopped.countDown(); });
        producer.start();
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            closer.start();
            assertFalse(stopped.await(10, java.util.concurrent.TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
            producer.join(5000);
            closer.join(5000);
        }
        assertEquals(0, stopped.getCount());
        assertEquals(1, workers.size());
    }

    @Test
    void urgentBoundaryConsumesTheDelayedTicketExactlyOnce() {
        var timers = new ArrayDeque<Runnable>();
        var workers = new ArrayDeque<Runnable>();
        var count = new AtomicInteger();
        var scheduler = new IndexWriteScheduler(workers::add, timers::add);
        scheduler.schedule(count::incrementAndGet, rejected -> fail(rejected), false);
        assertTrue(workers.isEmpty());
        scheduler.flush();
        scheduler.flush();
        assertEquals(1, workers.size());
        workers.remove().run();
        scheduler.schedule(count::incrementAndGet, rejected -> fail(rejected), false);
        timers.remove().run(); // The old delayed ticket must not consume the new one.
        assertTrue(workers.isEmpty());
        timers.remove().run();
        workers.remove().run();
        assertEquals(2, count.get());
    }

    @Test
    void rejectedWorkerReportsFailureAndTheNextWriteCanRun() {
        var failures = new AtomicInteger();
        var calls = new AtomicInteger();
        var scheduler = new IndexWriteScheduler(command -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("closed");
            command.run();
        }, Runnable::run);
        scheduler.schedule(() -> fail("Rejected"), rejected -> failures.incrementAndGet(), false);
        scheduler.flush();
        scheduler.schedule(() -> { }, rejected -> fail(rejected), true);
        assertEquals(1, failures.get());
        assertEquals(2, calls.get());
    }
}
