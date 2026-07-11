package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleplayerPerformanceMonitorTest {

    private static final Instant NOW = Instant.parse("2026-04-28T00:00:00Z");

    @Test
    void acceptsPatchReplayWorkloads() {
        SingleplayerPerformanceMonitor monitor = new SingleplayerPerformanceMonitor();
        monitor.recordSyncSlice("Project setup", Duration.ofMillis(20).toNanos());
        monitor.recordSyncSlice("Verify save", Duration.ofMillis(30).toNanos());
        monitor.recordOperationSnapshot(snapshot("quick-rollback", 3));
        monitor.recordOperationSnapshot(snapshot("partial-restore", 1));
        monitor.recordOperationSnapshot(snapshot("restore-version", 4));

        assertTrue(monitor.checks().stream().allMatch(SingleplayerPerformanceMonitor.PerformanceCheck::passed));
    }

    @Test
    void keepsQuickRollbackDraftsBounded() {
        SingleplayerPerformanceMonitor accepted = new SingleplayerPerformanceMonitor();
        accepted.recordOperationSnapshot(snapshot("quick-rollback", 128));

        SingleplayerPerformanceMonitor rejected = new SingleplayerPerformanceMonitor();
        rejected.recordOperationSnapshot(snapshot("quick-rollback", 129));

        assertTrue(checkContaining(accepted, "remained draft-scoped").passed());
        assertFalse(checkContaining(rejected, "remained draft-scoped").passed());
    }

    @Test
    void flagsSlowPostSetupTickSlicesAsTpsRegression() {
        SingleplayerPerformanceMonitor monitor = new SingleplayerPerformanceMonitor();
        monitor.recordSyncSlice("Project setup", Duration.ofSeconds(2).toNanos());
        monitor.recordSyncSlice("Break grass support fallout", Duration.ofMillis(1_500).toNanos());

        SingleplayerPerformanceMonitor.PerformanceCheck tickCheck = checkContaining(
                monitor,
                "Largest post-project Lumi test tick slice"
        );

        assertFalse(tickCheck.passed());
        assertTrue(tickCheck.detail().contains("Break grass support fallout"));
    }

    @Test
    void flagsAccumulatedSaveAndWorldLoadSyncOverhead() {
        SingleplayerPerformanceMonitor monitor = new SingleplayerPerformanceMonitor();
        monitor.recordSyncSlice("Open world", Duration.ofMillis(900).toNanos());
        monitor.recordSyncSlice("Create world workspace", Duration.ofMillis(900).toNanos());
        monitor.recordSyncSlice("Verify save", Duration.ofMillis(900).toNanos());
        monitor.recordSyncSlice("Reopen world", Duration.ofMillis(900).toNanos());
        monitor.recordSyncSlice("Load project home", Duration.ofMillis(900).toNanos());
        monitor.recordSyncSlice("Load save details", Duration.ofMillis(900).toNanos());

        SingleplayerPerformanceMonitor.PerformanceCheck totalCheck = checkContaining(
                monitor,
                "Total synchronous Lumi test overhead"
        );

        assertFalse(totalCheck.passed());
        assertTrue(totalCheck.detail().contains("5400"));
    }

    @Test
    void flagsFailedSaveRestoreAndWorldOperations() {
        SingleplayerPerformanceMonitor monitor = new SingleplayerPerformanceMonitor();
        monitor.recordOperationSnapshot(snapshot("save-version", 1, OperationStage.FAILED, "Patch write failed"));

        SingleplayerPerformanceMonitor.PerformanceCheck failureCheck = checkContaining(
                monitor,
                "Recorded world operations completed without failure"
        );

        assertFalse(failureCheck.passed());
        assertTrue(failureCheck.detail().contains("save-version"));
    }

    @Test
    void enforcesTwoSecondCoreOperationBudgetIncludingQuickRollback() {
        SingleplayerPerformanceMonitor accepted = new SingleplayerPerformanceMonitor();
        accepted.recordOperationSnapshot(snapshot("restore-version", 10, 2_000));

        SingleplayerPerformanceMonitor rejected = new SingleplayerPerformanceMonitor();
        rejected.recordOperationSnapshot(snapshot("quick-rollback", 10, 2_001));

        assertTrue(checkContaining(accepted, "Core save, restore").passed());
        SingleplayerPerformanceMonitor.PerformanceCheck rejectedCheck =
                checkContaining(rejected, "Core save, restore");
        assertFalse(rejectedCheck.passed());
        assertTrue(rejectedCheck.detail().contains("2001 ms in quick-rollback"));
    }

    @Test
    void flagsLineageFullChunkRestoreAsLoadRegression() {
        SingleplayerPerformanceMonitor monitor = new SingleplayerPerformanceMonitor();
        monitor.recordSyncSlice("Project setup", Duration.ofMillis(20).toNanos());
        monitor.recordOperationSnapshot(snapshot("restore-version", 98_304));

        SingleplayerPerformanceMonitor.PerformanceCheck restoreCheck = monitor.checks().stream()
                .filter(check -> check.label().contains("Lineage full restore"))
                .findFirst()
                .orElseThrow();

        assertFalse(restoreCheck.passed());
        assertTrue(restoreCheck.detail().contains("98304"));
    }

    @Test
    void acceptsExactInitialSnapshotRestore() {
        SingleplayerPerformanceMonitor monitor = new SingleplayerPerformanceMonitor();
        monitor.recordSyncSlice("Project setup", Duration.ofMillis(20).toNanos());
        monitor.recordOperationSnapshot(snapshot(
                "restore-version",
                2,
                OperationStage.PREPARING,
                "Decoded exact initial snapshot snapshot-0001"
        ));
        monitor.recordOperationSnapshot(snapshot("restore-version", 98_304));

        SingleplayerPerformanceMonitor.PerformanceCheck restoreCheck = monitor.checks().stream()
                .filter(check -> check.label().contains("Lineage full restore"))
                .findFirst()
                .orElseThrow();

        assertTrue(restoreCheck.passed());
    }

    @Test
    void acceptsLegacyInitialSnapshotMarker() {
        SingleplayerPerformanceMonitor monitor = new SingleplayerPerformanceMonitor();
        monitor.recordSyncSlice("Project setup", Duration.ofMillis(20).toNanos());
        monitor.recordOperationSnapshot(snapshot(
                "restore-version",
                98_304,
                OperationStage.PREPARING,
                "Decoded initial snapshot snapshot-0001"
        ));

        SingleplayerPerformanceMonitor.PerformanceCheck restoreCheck = monitor.checks().stream()
                .filter(check -> check.label().contains("Lineage full restore"))
                .findFirst()
                .orElseThrow();

        assertTrue(restoreCheck.passed());
    }

    @Test
    void acceptsExactInitialSnapshotMarkerCarriedIntoApplyProgress() {
        SingleplayerPerformanceMonitor monitor = new SingleplayerPerformanceMonitor();
        monitor.recordSyncSlice("Project setup", Duration.ofMillis(20).toNanos());
        monitor.recordOperationSnapshot(snapshot(
                "restore-version",
                98_304,
                OperationStage.APPLYING,
                "Decoded initial snapshot snapshot-0001; Applying chunk 0:0"
        ));

        SingleplayerPerformanceMonitor.PerformanceCheck restoreCheck = monitor.checks().stream()
                .filter(check -> check.label().contains("Lineage full restore"))
                .findFirst()
                .orElseThrow();

        assertTrue(restoreCheck.passed());
    }

    @Test
    void flagsHeapGrowthAcrossRuntimeLoadSamples() {
        SingleplayerPerformanceMonitor monitor = new SingleplayerPerformanceMonitor();
        monitor.recordLoadSample("start", loadSample(256, 64, 8, 0, 32, 0L, 0L));
        monitor.recordLoadSample("after first interaction", loadSample(1_400, 70, 8, 0, 33, 10_000_000L, 100_000_000L));

        SingleplayerPerformanceMonitor.PerformanceCheck heapCheck = checkContaining(
                monitor,
                "JVM heap growth"
        );

        assertFalse(heapCheck.passed());
        assertTrue(heapCheck.detail().contains("1144"));
    }

    @Test
    void flagsLiveThreadGrowthAcrossRuntimeLoadSamples() {
        SingleplayerPerformanceMonitor monitor = new SingleplayerPerformanceMonitor();
        monitor.recordLoadSample("start", loadSample(256, 64, 8, 0, 32, 0L, 0L));
        monitor.recordLoadSample("after first interaction", loadSample(260, 64, 8, 0, 54, 10_000_000L, 100_000_000L));

        SingleplayerPerformanceMonitor.PerformanceCheck threadCheck = checkContaining(
                monitor,
                "Live thread growth"
        );

        assertFalse(threadCheck.passed());
        assertTrue(threadCheck.detail().contains("22"));
    }

    @Test
    void reportsMaxProcessCpuPeakWindow() {
        SingleplayerPerformanceMonitor monitor = new SingleplayerPerformanceMonitor();
        monitor.recordLoadSample("start", loadSample(256, 64, 8, 0, 32, 0L, 0L));
        monitor.recordLoadSample("warmup", loadSample(256, 64, 8, 0, 32, Duration.ofMillis(50).toNanos(), Duration.ofMillis(50).toNanos()));
        monitor.recordLoadSample("capture", loadSample(256, 64, 8, 0, 32, Duration.ofMillis(450).toNanos(), Duration.ofMillis(100).toNanos()));

        String summary = monitor.summaryLines().stream()
                .filter(line -> line.startsWith("Load summary:"))
                .findFirst()
                .orElseThrow();

        assertTrue(summary.contains("maxProcessCpuCores=8.00"));
        assertTrue(summary.contains("maxProcessCpuWindow=warmup -> capture"));
        assertTrue(summary.contains("maxProcessCpuWallMs=50"));
        assertTrue(summary.contains("maxProcessCpuTimeMs=400"));
    }

    @Test
    void flagsSlowFirstWorldInteractionCpuProbe() {
        SingleplayerPerformanceMonitor monitor = new SingleplayerPerformanceMonitor();
        monitor.recordFirstInteraction("capture draft", Duration.ofMillis(75).toNanos(), Duration.ofMillis(1_250).toNanos());

        SingleplayerPerformanceMonitor.PerformanceCheck cpuCheck = checkContaining(
                monitor,
                "First world interaction CPU"
        );

        assertFalse(cpuCheck.passed());
        assertTrue(cpuCheck.detail().contains("1250"));
    }

    private static OperationSnapshot snapshot(String label, int totalUnits) {
        return snapshot(label, totalUnits, OperationStage.COMPLETED, "Completed");
    }

    private static OperationSnapshot snapshot(String label, int totalUnits, long durationMillis) {
        return new OperationSnapshot(
                new OperationHandle(label + "-id", "project", label, NOW, false),
                OperationStage.COMPLETED,
                new OperationProgress(totalUnits, totalUnits, "blocks"),
                "Completed",
                NOW.plusMillis(durationMillis)
        );
    }

    private static OperationSnapshot snapshot(String label, int totalUnits, OperationStage stage, String detail) {
        return new OperationSnapshot(
                new OperationHandle(label + "-id", "project", label, NOW, false),
                stage,
                new OperationProgress(totalUnits, totalUnits, "blocks"),
                detail,
                NOW.plusMillis(10)
        );
    }

    private static SingleplayerPerformanceMonitor.LoadSample loadSample(
            long heapUsedMiB,
            long nonHeapUsedMiB,
            long directBufferUsedMiB,
            long mappedBufferUsedMiB,
            int liveThreads,
            long processCpuTimeNanos,
            long wallNanos
    ) {
        return new SingleplayerPerformanceMonitor.LoadSample(
                heapUsedMiB,
                nonHeapUsedMiB,
                directBufferUsedMiB,
                mappedBufferUsedMiB,
                liveThreads,
                processCpuTimeNanos,
                wallNanos,
                8
        );
    }

    private static SingleplayerPerformanceMonitor.PerformanceCheck checkContaining(
            SingleplayerPerformanceMonitor monitor,
            String label
    ) {
        return monitor.checks().stream()
                .filter(check -> check.label().contains(label))
                .findFirst()
                .orElseThrow();
    }
}
