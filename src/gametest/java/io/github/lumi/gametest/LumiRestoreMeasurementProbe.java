package io.github.lumi.gametest;

import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.minecraft.world.RestoreApplyStatistics;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Records the same mutation boundaries for synchronized and ordinary clients. */
final class LumiRestoreMeasurementProbe implements AutoCloseable {
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong terminal = new AtomicLong();
    private final AtomicReference<RestoreApplyStatistics> statistics = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private LumiServerTickProbe ticks;
    private volatile long started;
    private long heapBefore;
    private long heapPeak;
    private long maximumTick;

    LumiRestoreMeasurementProbe(FabricDimensionRuntime runtime) {
        runtime.operations().observeNextEnqueue((ticket, ignored) -> {
            accepted.set(System.nanoTime());
            runtime.operations().observeTerminal(ticket, completed -> {
                terminal.set(System.nanoTime());
                var measured = completed.restoreStatistics();
                if (measured.isEmpty()) {
                    failure.compareAndSet(null, new AssertionError("Mutation completed without apply statistics"));
                } else {
                    statistics.set(measured.orElseThrow());
                }
            });
        });
    }

    void begin(LumiServerTickProbe ticks) {
        if (started != 0) throw new IllegalStateException("Measurement already started");
        this.ticks = ticks;
        heapBefore = usedHeap();
        heapPeak = heapBefore;
        started = System.nanoTime();
    }

    long startedNanos() { return started; }
    AtomicReference<Throwable> failure() { return failure; }
    void sampleHeap() { heapPeak = Math.max(heapPeak, usedHeap()); }
    boolean coversTerminalTick() { return terminal.get() != 0 && ticks != null && ticks.covers(terminal.get()); }

    LumiRestoreMeasurement measurement() {
        if (failure.get() != null) throw new AssertionError("Mutation measurement failed", failure.get());
        if (started == 0 || accepted.get() == 0 || terminal.get() == 0 || statistics.get() == null) {
            throw new AssertionError("Mutation metrics observer did not complete");
        }
        return new LumiRestoreMeasurement(millis(started, terminal.get()),
                millis(started, accepted.get()), millis(accepted.get(), terminal.get()),
                heapBefore, heapPeak, maximumTick, statistics.get());
    }

    @Override public void close() {
        if (ticks != null) {
            maximumTick = ticks.maximumNanos();
            ticks.close();
            ticks = null;
        }
    }

    private static long usedHeap() {
        var jvm = Runtime.getRuntime();
        return jvm.totalMemory() - jvm.freeMemory();
    }

    private static long millis(long from, long to) { return Math.max(0, to - from) / 1_000_000; }
}
