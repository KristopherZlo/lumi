package io.github.luma.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LoadLogSummaryTest {

    @Test
    void ranksEntriesByTotalRuntime() {
        LoadLogSummary summary = new LoadLogSummary();

        summary.record("world-op", "restore-version.applyTick", 1_000_000L);
        summary.record("world-op", "save-version.background", 3_000_000L);
        summary.record("world-op", "restore-version.applyTick", 2_000_000L);

        var top = summary.topByTotal(2);

        assertEquals("restore-version.applyTick", top.get(0).name());
        assertEquals(2L, top.get(0).count());
        assertEquals(3_000_000L, top.get(0).totalNanos());
        assertEquals("save-version.background", top.get(1).name());
    }

    @Test
    void normalizesBlankNames() {
        LoadLogSummary summary = new LoadLogSummary();

        summary.record("", " ", 1_000L);

        var top = summary.topByTotal(1);

        assertEquals("unknown", top.get(0).area());
        assertEquals("unknown", top.get(0).name());
    }
}
