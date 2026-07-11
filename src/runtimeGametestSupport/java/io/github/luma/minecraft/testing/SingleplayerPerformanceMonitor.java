package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.OperationSnapshot;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Captures lightweight runtime-load metrics for the singleplayer test suite.
 */
final class SingleplayerPerformanceMonitor {

    private static final long MAX_SYNC_SLICE_NANOS = Duration.ofSeconds(1).toNanos();
    private static final long MAX_SYNC_TOTAL_NANOS = Duration.ofSeconds(5).toNanos();
    private static final int MAX_QUICK_ROLLBACK_UNITS = 128;
    private static final int MAX_PARTIAL_RESTORE_BLOCKS = 16;
    private static final int MAX_FULL_RESTORE_BLOCKS = 512;
    private static final long MAX_HEAP_GROWTH_MIB = 1024;
    private static final long MAX_BUFFER_GROWTH_MIB = 128;
    private static final int MAX_THREAD_GROWTH = 16;
    private static final long MAX_FIRST_INTERACTION_NANOS = Duration.ofSeconds(1).toNanos();
    private static final long MAX_CORE_OPERATION_MILLIS = 2_000L;
    private static final Set<String> WARMUP_SYNC_PHASES = Set.of("Project setup");
    private static final Set<String> CORE_OPERATION_LABELS = Set.of(
            "save-version",
            "amend-version",
            "restore-version",
            "partial-restore",
            "zone-restore",
            "restore-draft",
            "quick-rollback",
            "merge-variant"
    );

    private final Map<String, OperationMetric> operations = new LinkedHashMap<>();
    private final List<LabeledLoadSample> loadSamples = new ArrayList<>();
    private long totalSyncNanos;
    private long maxSyncSliceNanos;
    private String maxSyncSlicePhase = "";
    private long maxBudgetedSyncSliceNanos;
    private String maxBudgetedSyncSlicePhase = "";
    private int syncSliceCount;
    private long firstInteractionWallNanos;
    private long firstInteractionCpuNanos = -1L;
    private String firstInteractionPhase = "";
    private boolean firstInteractionRecorded;
    private double maxProcessCpuCores;
    private String maxProcessCpuStartLabel = "";
    private String maxProcessCpuEndLabel = "";
    private long maxProcessCpuWallNanos = -1L;
    private long maxProcessCpuTimeNanos = -1L;

    void recordSyncSlice(String phase, long elapsedNanos) {
        if (elapsedNanos <= 0L) {
            return;
        }

        this.totalSyncNanos += elapsedNanos;
        this.syncSliceCount += 1;
        if (elapsedNanos > this.maxSyncSliceNanos) {
            this.maxSyncSliceNanos = elapsedNanos;
            this.maxSyncSlicePhase = phase == null || phase.isBlank() ? "unknown" : phase;
        }
        if (!this.warmupPhase(phase) && elapsedNanos > this.maxBudgetedSyncSliceNanos) {
            this.maxBudgetedSyncSliceNanos = elapsedNanos;
            this.maxBudgetedSyncSlicePhase = phase == null || phase.isBlank() ? "unknown" : phase;
        }
    }

    void recordOperationSnapshot(OperationSnapshot snapshot) {
        if (snapshot == null || snapshot.handle() == null) {
            return;
        }

        OperationMetric metric = this.operations.computeIfAbsent(
                snapshot.handle().id(),
                ignored -> new OperationMetric(snapshot.handle().label(), snapshot.handle().startedAt())
        );
        metric.record(snapshot);
    }

    void recordLoadSample(String label) {
        this.recordLoadSample(label, LoadSample.capture());
    }

    void recordLoadSample(String label, LoadSample sample) {
        if (sample == null) {
            return;
        }
        LabeledLoadSample labeledSample = new LabeledLoadSample(normalize(label), sample);
        if (!this.loadSamples.isEmpty()) {
            this.recordProcessCpuPeak(this.loadSamples.getLast(), labeledSample);
        }
        this.loadSamples.add(labeledSample);
    }

    void recordFirstInteraction(String phase, long wallNanos, long cpuNanos) {
        if (wallNanos <= 0L) {
            return;
        }
        this.firstInteractionRecorded = true;
        this.firstInteractionWallNanos = Math.max(this.firstInteractionWallNanos, wallNanos);
        this.firstInteractionCpuNanos = Math.max(this.firstInteractionCpuNanos, cpuNanos);
        this.firstInteractionPhase = normalize(phase);
    }

    List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Performance summary: syncSlices=" + this.syncSliceCount
                + ", syncTotalMs=" + this.millis(this.totalSyncNanos)
                + ", maxSyncSliceMs=" + this.millis(this.maxSyncSliceNanos)
                + ", maxSyncSlicePhase=" + this.maxSyncSlicePhase
                + ", maxBudgetedSyncSliceMs=" + this.millis(this.maxBudgetedSyncSliceNanos)
                + ", maxBudgetedSyncSlicePhase=" + this.maxBudgetedSyncSlicePhase);
        for (OperationMetric metric : this.operations.values()) {
            lines.add("Performance operation: label=" + metric.label
                    + ", ticks=" + metric.observedTicks
                    + ", durationMs=" + metric.durationMillis()
                    + ", maxUnits=" + metric.maxTotalUnits
                    + ", terminal=" + metric.terminal
                    + ", failed=" + metric.failed);
        }
        lines.add("Load summary: samples=" + this.loadSamples.size()
                + ", heapGrowthMiB=" + this.heapGrowthMiB()
                + ", bufferGrowthMiB=" + this.bufferGrowthMiB()
                + ", threadGrowth=" + this.threadGrowth()
                + ", maxProcessCpuCores=" + this.number(this.maxProcessCpuCores)
                + ", maxProcessCpuWindow=" + this.maxProcessCpuWindow()
                + ", maxProcessCpuWallMs=" + this.millisText(this.maxProcessCpuWallNanos)
                + ", maxProcessCpuTimeMs=" + this.millisText(this.maxProcessCpuTimeNanos)
                + ", firstInteractionWallMs=" + this.millisText(this.firstInteractionWallNanos)
                + ", firstInteractionCpuMs=" + this.millisText(this.firstInteractionCpuNanos)
                + ", firstInteractionPhase=" + this.firstInteractionPhase);
        for (LabeledLoadSample sample : this.loadSamples) {
            lines.add("Load sample: label=" + sample.label
                    + ", heapUsedMiB=" + sample.sample.heapUsedMiB()
                    + ", nonHeapUsedMiB=" + sample.sample.nonHeapUsedMiB()
                    + ", directBufferUsedMiB=" + sample.sample.directBufferUsedMiB()
                    + ", mappedBufferUsedMiB=" + sample.sample.mappedBufferUsedMiB()
                    + ", liveThreads=" + sample.sample.liveThreads()
                    + ", processCpuTimeMs=" + this.millisText(sample.sample.processCpuTimeNanos()));
        }
        return List.copyOf(lines);
    }

    List<PerformanceCheck> checks() {
        List<PerformanceCheck> checks = new ArrayList<>();
        checks.add(new PerformanceCheck(
                "Largest post-project Lumi test tick slice stayed below " + this.millis(MAX_SYNC_SLICE_NANOS) + " ms",
                this.maxBudgetedSyncSliceNanos <= MAX_SYNC_SLICE_NANOS,
                "max=" + this.millis(this.maxBudgetedSyncSliceNanos) + " ms in "
                        + this.maxBudgetedSyncSlicePhase
                        + ", observedMax=" + this.millis(this.maxSyncSliceNanos) + " ms in " + this.maxSyncSlicePhase
        ));
        checks.add(new PerformanceCheck(
                "Total synchronous Lumi test overhead stayed below " + this.millis(MAX_SYNC_TOTAL_NANOS) + " ms",
                this.totalSyncNanos <= MAX_SYNC_TOTAL_NANOS,
                "total=" + this.millis(this.totalSyncNanos) + " ms across " + this.syncSliceCount + " slices"
        ));
        checks.add(new PerformanceCheck(
                "Recorded world operations completed without failure",
                this.failedOperationLabels().isEmpty(),
                "failedOperations=" + this.failedOperationLabels()
        ));
        OperationMetric slowestCoreOperation = this.slowestCoreOperation();
        checks.add(new PerformanceCheck(
                "Core save, restore, branch, and zone operations stayed below "
                        + MAX_CORE_OPERATION_MILLIS + " ms",
                slowestCoreOperation == null || slowestCoreOperation.durationMillis() <= MAX_CORE_OPERATION_MILLIS,
                slowestCoreOperation == null
                        ? "no core operations recorded"
                        : "max=" + slowestCoreOperation.durationMillis() + " ms in " + slowestCoreOperation.label
        ));
        checks.add(new PerformanceCheck(
                "Quick rollback remained draft-scoped instead of broad world work",
                this.maxOperationUnits("quick-rollback") <= MAX_QUICK_ROLLBACK_UNITS,
                "maxQuickRollbackUnits=" + this.maxOperationUnits("quick-rollback")
        ));
        checks.add(new PerformanceCheck(
                "Partial restore stayed region-scoped",
                this.maxOperationUnits("partial-restore") <= MAX_PARTIAL_RESTORE_BLOCKS,
                "maxPartialRestoreUnits=" + this.maxOperationUnits("partial-restore")
        ));
        checks.add(new PerformanceCheck(
                "Lineage full restore used patch replay unless exact initial snapshot replay was required",
                this.maxRestoreUnitsWithoutInitialSnapshot() <= MAX_FULL_RESTORE_BLOCKS,
                "maxLineageRestoreUnits=" + this.maxRestoreUnitsWithoutInitialSnapshot()
        ));
        checks.add(new PerformanceCheck(
                "JVM heap growth during Lumi smoke stayed below " + MAX_HEAP_GROWTH_MIB + " MiB",
                this.loadSamples.size() < 2 || this.heapGrowthMiB() <= MAX_HEAP_GROWTH_MIB,
                "heapGrowthMiB=" + this.heapGrowthMiB() + ", samples=" + this.loadSamples.size()
        ));
        checks.add(new PerformanceCheck(
                "Direct and mapped buffer growth during Lumi smoke stayed below " + MAX_BUFFER_GROWTH_MIB + " MiB",
                this.loadSamples.size() < 2 || this.bufferGrowthMiB() <= MAX_BUFFER_GROWTH_MIB,
                "bufferGrowthMiB=" + this.bufferGrowthMiB() + ", samples=" + this.loadSamples.size()
        ));
        checks.add(new PerformanceCheck(
                "Live thread growth during Lumi smoke stayed below " + MAX_THREAD_GROWTH + " threads",
                this.loadSamples.size() < 2 || this.threadGrowth() <= MAX_THREAD_GROWTH,
                "threadGrowth=" + this.threadGrowth() + ", samples=" + this.loadSamples.size()
        ));
        checks.add(new PerformanceCheck(
                "First world interaction wall time stayed below " + this.millis(MAX_FIRST_INTERACTION_NANOS) + " ms",
                !this.firstInteractionRecorded || this.firstInteractionWallNanos <= MAX_FIRST_INTERACTION_NANOS,
                "wallMs=" + this.millisText(this.firstInteractionWallNanos) + ", phase=" + this.firstInteractionPhase
        ));
        checks.add(new PerformanceCheck(
                "First world interaction CPU stayed below " + this.millis(MAX_FIRST_INTERACTION_NANOS) + " ms",
                !this.firstInteractionRecorded
                        || this.firstInteractionCpuNanos < 0L
                        || this.firstInteractionCpuNanos <= MAX_FIRST_INTERACTION_NANOS,
                "cpuMs=" + this.millisText(this.firstInteractionCpuNanos) + ", phase=" + this.firstInteractionPhase
        ));
        return List.copyOf(checks);
    }

    private int maxOperationUnits(String... labels) {
        int max = 0;
        for (OperationMetric metric : this.operations.values()) {
            for (String label : labels) {
                if (label.equals(metric.label)) {
                    max = Math.max(max, metric.maxTotalUnits);
                }
            }
        }
        return max;
    }

    private List<String> failedOperationLabels() {
        List<String> labels = new ArrayList<>();
        for (OperationMetric metric : this.operations.values()) {
            if (metric.failed) {
                labels.add(metric.label);
            }
        }
        return labels;
    }

    private OperationMetric slowestCoreOperation() {
        OperationMetric slowest = null;
        for (OperationMetric metric : this.operations.values()) {
            if (!CORE_OPERATION_LABELS.contains(metric.label)) {
                continue;
            }
            if (slowest == null || metric.durationMillis() > slowest.durationMillis()) {
                slowest = metric;
            }
        }
        return slowest;
    }

    private int maxRestoreUnitsWithoutInitialSnapshot() {
        int max = 0;
        for (OperationMetric metric : this.operations.values()) {
            if ("restore-version".equals(metric.label) && !metric.decodedInitialSnapshot) {
                max = Math.max(max, metric.maxTotalUnits);
            }
        }
        return max;
    }

    private long millis(long nanos) {
        return Duration.ofNanos(Math.max(0L, nanos)).toMillis();
    }

    private String millisText(long nanos) {
        return nanos < 0L ? "na" : String.valueOf(this.millis(nanos));
    }

    private long heapGrowthMiB() {
        if (this.loadSamples.size() < 2) {
            return 0L;
        }
        long baseline = this.loadSamples.getFirst().sample.heapUsedMiB();
        return Math.max(0L, this.loadSamples.getLast().sample.heapUsedMiB() - baseline);
    }

    private long bufferGrowthMiB() {
        if (this.loadSamples.size() < 2) {
            return 0L;
        }
        long baseline = this.bufferMiB(this.loadSamples.getFirst().sample);
        return Math.max(0L, this.bufferMiB(this.loadSamples.getLast().sample) - baseline);
    }

    private int threadGrowth() {
        if (this.loadSamples.size() < 2) {
            return 0;
        }
        int baseline = this.loadSamples.getFirst().sample.liveThreads();
        return Math.max(0, this.loadSamples.getLast().sample.liveThreads() - baseline);
    }

    private void recordProcessCpuPeak(LabeledLoadSample previous, LabeledLoadSample sample) {
        if (previous.sample.processCpuTimeNanos() < 0L
                || sample.sample.processCpuTimeNanos() < previous.sample.processCpuTimeNanos()
                || sample.sample.wallNanos() <= previous.sample.wallNanos()) {
            return;
        }
        long cpuDelta = sample.sample.processCpuTimeNanos() - previous.sample.processCpuTimeNanos();
        long wallDelta = sample.sample.wallNanos() - previous.sample.wallNanos();
        double cores = (double) cpuDelta / (double) wallDelta;
        if (cores <= this.maxProcessCpuCores) {
            return;
        }
        this.maxProcessCpuCores = cores;
        this.maxProcessCpuStartLabel = previous.label;
        this.maxProcessCpuEndLabel = sample.label;
        this.maxProcessCpuWallNanos = wallDelta;
        this.maxProcessCpuTimeNanos = cpuDelta;
    }

    private String maxProcessCpuWindow() {
        return this.maxProcessCpuStartLabel.isBlank()
                ? "na"
                : this.maxProcessCpuStartLabel + " -> " + this.maxProcessCpuEndLabel;
    }

    private long bufferMiB(LoadSample sample) {
        return Math.max(0L, sample.directBufferUsedMiB()) + Math.max(0L, sample.mappedBufferUsedMiB());
    }

    private String number(double value) {
        return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.2f", value) : "na";
    }

    private boolean warmupPhase(String phase) {
        return phase != null && WARMUP_SYNC_PHASES.contains(phase);
    }

    static long currentThreadCpuNanos() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        if (!threads.isCurrentThreadCpuTimeSupported()) {
            return -1L;
        }
        if (!threads.isThreadCpuTimeEnabled()) {
            try {
                threads.setThreadCpuTimeEnabled(true);
            } catch (UnsupportedOperationException | SecurityException ignored) {
                return -1L;
            }
        }
        return threads.isThreadCpuTimeEnabled() ? threads.getCurrentThreadCpuTime() : -1L;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    record PerformanceCheck(String label, boolean passed, String detail) {
    }

    record LoadSample(
            long heapUsedMiB,
            long nonHeapUsedMiB,
            long directBufferUsedMiB,
            long mappedBufferUsedMiB,
            int liveThreads,
            long processCpuTimeNanos,
            long wallNanos,
            int availableProcessors
    ) {

        private static LoadSample capture() {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
            return new LoadSample(
                    toMiB(heap.getUsed()),
                    toMiB(nonHeap.getUsed()),
                    bufferMiB("direct"),
                    bufferMiB("mapped"),
                    ManagementFactory.getThreadMXBean().getThreadCount(),
                    currentProcessCpuTimeNanos(),
                    System.nanoTime(),
                    Runtime.getRuntime().availableProcessors()
            );
        }

        private static long currentProcessCpuTimeNanos() {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            return bean instanceof com.sun.management.OperatingSystemMXBean os ? os.getProcessCpuTime() : -1L;
        }

        private static long bufferMiB(String name) {
            for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                if (pool.getName().equalsIgnoreCase(name)) {
                    return toMiB(pool.getMemoryUsed());
                }
            }
            return -1L;
        }

        private static long toMiB(long bytes) {
            return bytes < 0L ? -1L : bytes / (1024L * 1024L);
        }
    }

    private static final class LabeledLoadSample {

        private final String label;
        private final LoadSample sample;

        private LabeledLoadSample(String label, LoadSample sample) {
            this.label = label;
            this.sample = sample;
        }
    }

    private static final class OperationMetric {

        private final String label;
        private final Instant startedAt;
        private int observedTicks;
        private int maxTotalUnits;
        private Instant lastUpdatedAt;
        private boolean terminal;
        private boolean failed;
        private boolean decodedInitialSnapshot;

        private OperationMetric(String label, Instant startedAt) {
            this.label = label == null || label.isBlank() ? "unknown" : label;
            this.startedAt = startedAt == null ? Instant.now() : startedAt;
        }

        private void record(OperationSnapshot snapshot) {
            this.observedTicks += 1;
            this.maxTotalUnits = Math.max(this.maxTotalUnits, snapshot.progress().totalUnits());
            this.lastUpdatedAt = snapshot.updatedAt();
            this.terminal = this.terminal || snapshot.terminal();
            this.failed = this.failed || snapshot.failed();
            this.decodedInitialSnapshot = this.decodedInitialSnapshot
                    || decodedInitialSnapshot(snapshot.detail());
        }

        private boolean decodedInitialSnapshot(String detail) {
            return detail != null
                    && (detail.startsWith("Decoded initial snapshot")
                    || detail.startsWith("Decoded exact initial snapshot"));
        }

        private long durationMillis() {
            Instant end = this.lastUpdatedAt == null ? Instant.now() : this.lastUpdatedAt;
            return Duration.between(this.startedAt, end).toMillis();
        }
    }
}
