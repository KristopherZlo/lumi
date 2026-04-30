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
        monitor.recordOperationSnapshot(snapshot("undo-action", 3));
        monitor.recordOperationSnapshot(snapshot("redo-action", 3));
        monitor.recordOperationSnapshot(snapshot("partial-restore", 1));
        monitor.recordOperationSnapshot(snapshot("restore-version", 4));

        assertTrue(monitor.checks().stream().allMatch(SingleplayerPerformanceMonitor.PerformanceCheck::passed));
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
                "Decoded initial snapshot snapshot-0001"
        ));
        monitor.recordOperationSnapshot(snapshot("restore-version", 98_304));

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

    private static OperationSnapshot snapshot(String label, int totalUnits) {
        return snapshot(label, totalUnits, OperationStage.COMPLETED, "Completed");
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
}
