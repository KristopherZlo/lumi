package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ServerTickWorkSchedulerTest {
    @Test
    void sharesThirtyMillisecondsAndRotatesWorkAcrossDimensions() throws Exception {
        var clock = new AtomicLong();
        var scheduler = new ServerTickWorkScheduler<String>(clock::get, 30_000_000L);
        var advanced = new ArrayList<String>();
        var visited = new ArrayList<String>();
        for (int tick = 0; tick < 4; tick++) {
            long beginning = clock.get();
            scheduler.tick(List.of("overworld", "nether"), (dimension, deadline) -> {
                visited.add(dimension);
                if (clock.get() < deadline) {
                    advanced.add(dimension);
                    clock.set(deadline);
                }
            });
            assertEquals(30_000_000L, clock.get() - beginning);
        }
        assertEquals(List.of("overworld", "nether", "overworld", "nether"), advanced);
        assertEquals(8, visited.size());
    }

    @Test
    void keepsOtherDimensionHousekeepingAfterAnIoFailureAndHandlesUnloads() throws Exception {
        var scheduler = new ServerTickWorkScheduler<String>(() -> 10L, 30L);
        var visited = new ArrayList<String>();
        var first = new IOException("first");
        var second = new IOException("second");
        var failure = assertThrows(IOException.class, () ->
                scheduler.tick(List.of("a", "b"), (dimension, deadline) -> {
                    assertEquals(40L, deadline);
                    visited.add(dimension);
                    throw dimension.equals("a") ? first : second;
                }));
        assertSame(first, failure);
        assertArrayEquals(new Throwable[] {second}, failure.getSuppressed());
        scheduler.tick(List.of(), (dimension, deadline) -> fail("Empty registry"));
        scheduler.tick(List.of("b"), (dimension, deadline) -> visited.add(dimension));
        assertEquals(List.of("a", "b", "b"), visited);
    }
}
