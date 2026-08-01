package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.storage.repository.GarbageCollectionResult;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GarbageCollectionSchedulerTest {
    @Test
    void schedulesMaintenanceOffTickWithPhaseSpecificBusyState() {
        var queued = new ArrayList<Runnable>();
        AtomicInteger compactions = new AtomicInteger();
        AtomicInteger compactedPacks = new AtomicInteger();
        AtomicInteger collections = new AtomicInteger();
        var results = new ArrayList<GarbageCollectionResult>();
        var failures = new ArrayList<Throwable>();
        GarbageCollectionScheduler scheduler = new GarbageCollectionScheduler(
                0, queued::add,
                () -> {
                    compactions.incrementAndGet();
                    return 7;
                }, compactedPacks::set,
                () -> {
                    collections.incrementAndGet();
                    return new GarbageCollectionResult(2, 3, 4);
                },
                results::add, failures::add);

        scheduler.tick(GarbageCollectionScheduler.STARTUP_COMPACTION_DELAY_TICKS - 1,
                false, false);
        assertEquals(0, queued.size());
        scheduler.tick(GarbageCollectionScheduler.STARTUP_COMPACTION_DELAY_TICKS,
                true, true);
        assertEquals(0, queued.size());
        scheduler.tick(GarbageCollectionScheduler.STARTUP_COMPACTION_DELAY_TICKS
                + GarbageCollectionScheduler.RETRY_TICKS, false, true);
        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertEquals(1, compactions.get());
        assertEquals(7, compactedPacks.get());

        scheduler.tick(GarbageCollectionScheduler.INITIAL_DELAY_TICKS,
                false, true);
        scheduler.tick(
                GarbageCollectionScheduler.INITIAL_DELAY_TICKS
                        + GarbageCollectionScheduler.RETRY_TICKS,
                false, false);
        scheduler.tick(Long.MAX_VALUE, false, false);

        assertEquals(0, collections.get());
        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertEquals(1, collections.get());
        assertEquals(java.util.List.of(new GarbageCollectionResult(2, 3, 4)), results);
        assertEquals(java.util.List.of(), failures);
    }
}
