package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.storage.repository.GarbageCollectionResult;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GarbageCollectionSchedulerTest {
    @Test
    void schedulesOnlyWhenIdleAndNeverRunsCollectionOnTheTickCaller() {
        var queued = new ArrayList<Runnable>();
        AtomicInteger collections = new AtomicInteger();
        var results = new ArrayList<GarbageCollectionResult>();
        var failures = new ArrayList<Throwable>();
        GarbageCollectionScheduler scheduler = new GarbageCollectionScheduler(
                0, queued::add,
                () -> {
                    collections.incrementAndGet();
                    return new GarbageCollectionResult(2, 3, 4);
                },
                results::add, failures::add);

        scheduler.tick(GarbageCollectionScheduler.INITIAL_DELAY_TICKS, true);
        scheduler.tick(
                GarbageCollectionScheduler.INITIAL_DELAY_TICKS
                        + GarbageCollectionScheduler.RETRY_TICKS,
                false);
        scheduler.tick(Long.MAX_VALUE, false);

        assertEquals(0, collections.get());
        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertEquals(1, collections.get());
        assertEquals(java.util.List.of(new GarbageCollectionResult(2, 3, 4)), results);
        assertEquals(java.util.List.of(), failures);
    }
}
