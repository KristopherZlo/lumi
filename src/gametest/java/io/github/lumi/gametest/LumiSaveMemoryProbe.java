package io.github.lumi.gametest;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in process-heap sampling; retains no captured world or sample collection. */
final class LumiSaveMemoryProbe implements AutoCloseable {
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "Lumi-Save-memory-probe");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicLong peak = new AtomicLong();
    private final AtomicLong samples = new AtomicLong();
    private final long before = heap();
    private final long gcMillis = gcMillis();
    private boolean closed;

    LumiSaveMemoryProbe() {
        peak.set(before);
        sampler.scheduleAtFixedRate(this::sample, 0, 2, TimeUnit.MILLISECONDS);
    }

    void report(String name, LumiBehaviorReport report) throws IOException {
        close();
        long after = heap();
        long collections = gcCount();
        boolean requestGc = Boolean.getBoolean("lumi.benchmark.memoryGc");
        if (requestGc) System.gc();
        long live = heap();
        boolean observedGc = gcCount() > collections;
        report.event("save_memory", name, "measured", 0, 0,
                "scope=client-and-integrated-server;sampleIntervalMillis=2"
                        + ";samples=" + samples.get() + ";heapBeforeBytes=" + before
                        + ";sampledPeakHeapBytes=" + peak.get() + ";heapAfterBytes=" + after
                        + ";postGcHeapBytes=" + live + ";gcRequested=" + requestGc
                        + ";gcObserved=" + observedGc + ";gcMillis=" + (gcMillis() - gcMillis)
                        + ";retainedObjectGraph=unverified");
        if (samples.get() == 0 || peak.get() < before) {
            throw new AssertionError("Save memory probe produced no valid samples");
        }
        String selected = System.getProperty("lumi.benchmark.heapDumpSave", "");
        if (name.equals(selected)) {
            var path = report.directory().resolve("save-after-live.hprof").toAbsolutePath();
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .dumpHeap(path.toString(), true);
            report.event("heap_dump", name, "written", 0, 0, path.toString());
        }
    }

    private void sample() {
        peak.accumulateAndGet(heap(), Math::max);
        samples.incrementAndGet();
    }

    private static long heap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        sampler.shutdown();
        try {
            if (!sampler.awaitTermination(5, TimeUnit.SECONDS)) {
                sampler.shutdownNow();
                throw new IllegalStateException("Save memory sampler did not terminate");
            }
        } catch (InterruptedException interrupted) {
            sampler.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing Save memory probe", interrupted);
        }
        sample();
    }
}
