package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.OperationSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures lightweight runtime-load metrics for the singleplayer test suite.
 */
final class SingleplayerPerformanceMonitor {

    private static final long MAX_SYNC_SLICE_NANOS = Duration.ofSeconds(1).toNanos();
    private static final long MAX_SYNC_TOTAL_NANOS = Duration.ofSeconds(5).toNanos();
    private static final int MAX_ACTION_APPLY_BLOCKS = 16;
    private static final int MAX_PARTIAL_RESTORE_BLOCKS = 16;
    private static final int MAX_FULL_RESTORE_BLOCKS = 512;

    private final Map<String, OperationMetric> operations = new LinkedHashMap<>();
    private long totalSyncNanos;
    private long maxSyncSliceNanos;
    private String maxSyncSlicePhase = "";
    private int syncSliceCount;

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

    List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Performance summary: syncSlices=" + this.syncSliceCount
                + ", syncTotalMs=" + this.millis(this.totalSyncNanos)
                + ", maxSyncSliceMs=" + this.millis(this.maxSyncSliceNanos)
                + ", maxSyncSlicePhase=" + this.maxSyncSlicePhase);
        for (OperationMetric metric : this.operations.values()) {
            lines.add("Performance operation: label=" + metric.label
                    + ", ticks=" + metric.observedTicks
                    + ", durationMs=" + metric.durationMillis()
                    + ", maxUnits=" + metric.maxTotalUnits
                    + ", terminal=" + metric.terminal
                    + ", failed=" + metric.failed);
        }
        return List.copyOf(lines);
    }

    List<PerformanceCheck> checks() {
        List<PerformanceCheck> checks = new ArrayList<>();
        checks.add(new PerformanceCheck(
                "Largest Lumi test tick slice stayed below " + this.millis(MAX_SYNC_SLICE_NANOS) + " ms",
                this.maxSyncSliceNanos <= MAX_SYNC_SLICE_NANOS,
                "max=" + this.millis(this.maxSyncSliceNanos) + " ms in " + this.maxSyncSlicePhase
        ));
        checks.add(new PerformanceCheck(
                "Total synchronous Lumi test overhead stayed below " + this.millis(MAX_SYNC_TOTAL_NANOS) + " ms",
                this.totalSyncNanos <= MAX_SYNC_TOTAL_NANOS,
                "total=" + this.millis(this.totalSyncNanos) + " ms across " + this.syncSliceCount + " slices"
        ));
        checks.add(new PerformanceCheck(
                "Undo and redo remained action-scoped instead of broad world work",
                this.maxOperationUnits("undo-action", "redo-action") <= MAX_ACTION_APPLY_BLOCKS,
                "maxActionUnits=" + this.maxOperationUnits("undo-action", "redo-action")
        ));
        checks.add(new PerformanceCheck(
                "Partial restore stayed region-scoped",
                this.maxOperationUnits("partial-restore") <= MAX_PARTIAL_RESTORE_BLOCKS,
                "maxPartialRestoreUnits=" + this.maxOperationUnits("partial-restore")
        ));
        checks.add(new PerformanceCheck(
                "Full restore used patch replay instead of full-chunk snapshot apply",
                this.maxOperationUnits("restore-version") <= MAX_FULL_RESTORE_BLOCKS,
                "maxRestoreUnits=" + this.maxOperationUnits("restore-version")
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

    private long millis(long nanos) {
        return Duration.ofNanos(Math.max(0L, nanos)).toMillis();
    }

    record PerformanceCheck(String label, boolean passed, String detail) {
    }

    private static final class OperationMetric {

        private final String label;
        private final Instant startedAt;
        private int observedTicks;
        private int maxTotalUnits;
        private Instant lastUpdatedAt;
        private boolean terminal;
        private boolean failed;

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
        }

        private long durationMillis() {
            Instant end = this.lastUpdatedAt == null ? Instant.now() : this.lastUpdatedAt;
            return Duration.between(this.startedAt, end).toMillis();
        }
    }
}
