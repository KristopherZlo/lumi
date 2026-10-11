package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IndexWriteSchedulerTest {
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
