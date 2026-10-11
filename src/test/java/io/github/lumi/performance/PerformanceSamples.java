package io.github.lumi.performance;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Opt-in component timings; raw samples accompany percentiles and allocation counts. */
final class PerformanceSamples {
    private final String workload;
    private final int units;
    private final List<Sample> samples = new ArrayList<>();
    private final ThreadMXBean allocations;

    PerformanceSamples(String workload, int units) {
        this.workload = workload;
        this.units = units;
        var bean = ManagementFactory.getThreadMXBean();
        allocations = bean instanceof ThreadMXBean tracked
                && tracked.isThreadAllocatedMemorySupported() ? tracked : null;
        if (allocations != null && !allocations.isThreadAllocatedMemoryEnabled()) {
            allocations.setThreadAllocatedMemoryEnabled(true);
        }
    }

    void measure(Work work) throws Exception {
        long beforeBytes = allocatedBytes();
        long start = System.nanoTime();
        long verified = work.run();
        long elapsed = System.nanoTime() - start;
        long bytes = allocations == null ? -1 : allocatedBytes() - beforeBytes;
        samples.add(new Sample(elapsed, bytes, verified));
    }

    long percentile(double percentile) {
        var ordered = samples.stream().sorted(Comparator.comparingLong(Sample::nanos)).toList();
        return ordered.get((int) Math.ceil(ordered.size() * percentile) - 1).nanos();
    }

    void write() throws Exception {
        StringBuilder csv = new StringBuilder("sample,units,nanos,allocated_bytes,verified\n");
        for (int index = 0; index < samples.size(); index++) {
            Sample sample = samples.get(index);
            csv.append(index).append(',').append(units).append(',')
                    .append(sample.nanos()).append(',').append(sample.bytes()).append(',')
                    .append(sample.verified()).append('\n');
        }
        csv.append("p50,").append(units).append(',').append(percentile(0.50)).append(",,\n")
                .append("p95,").append(units).append(',').append(percentile(0.95)).append(",,\n");
        Files.writeString(reportPath(workload + ".csv"), csv);
        System.out.printf("%s: p50=%.3f ms p95=%.3f ms (%d units, %d samples, Java %s)%n",
                workload, percentile(0.50) / 1_000_000.0, percentile(0.95) / 1_000_000.0,
                units, samples.size(), System.getProperty("java.version"));
    }

    static Path reportPath(String name) throws Exception {
        Path directory = Path.of(System.getProperty("lumi.regression.reportDir",
                "build/reports/lumi-regressions")).toAbsolutePath();
        Files.createDirectories(directory);
        return directory.resolve(name);
    }

    private long allocatedBytes() {
        return allocations == null ? -1 : allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    @FunctionalInterface interface Work { long run() throws Exception; }
    private record Sample(long nanos, long bytes, long verified) { }
}
