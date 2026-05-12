package io.github.luma.client.diagnostics;

import java.time.Instant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ClientRuntimeLoadDiagnosticsTest {

    @Test
    void frameWindowReportsAverageP95MaxAndFps() {
        ClientFrameTimeWindow window = new ClientFrameTimeWindow(8);

        window.record(16_000_000L);
        window.record(20_000_000L);
        window.record(40_000_000L);

        ClientFrameStats stats = window.snapshotAndReset();

        Assertions.assertEquals(3, stats.samples());
        Assertions.assertEquals(40.0D, stats.maxMillis(), 0.01D);
        Assertions.assertTrue(stats.averageMillis() > 24.0D);
        Assertions.assertTrue(stats.p95Millis() >= 40.0D);
        Assertions.assertTrue(stats.estimatedFps() > 0.0D);
        Assertions.assertEquals(0, window.snapshotAndReset().samples());
    }

    @Test
    void parsesNvidiaSmiCsvOutput() {
        ClientGpuMetrics metrics = NvidiaSmiClientGpuMetricsProbe.parse("""
                17, 512, 8192
                42, 2048, 16384
                """);

        Assertions.assertTrue(metrics.available());
        Assertions.assertEquals("nvidia-smi", metrics.provider());
        Assertions.assertEquals(42.0D, metrics.utilizationPercent(), 0.01D);
        Assertions.assertEquals(2560L, metrics.memoryUsedMiB());
        Assertions.assertEquals(24576L, metrics.memoryTotalMiB());
        Assertions.assertEquals("gpus=2", metrics.detail());
    }

    @Test
    void sampleLineContainsCpuMemoryFrameAndGpuFields() {
        ClientRuntimeLoadSnapshot snapshot = new ClientRuntimeLoadSnapshot(
                Instant.parse("2026-05-13T10:15:30Z"),
                "minecraft:overworld",
                "none",
                1920,
                1080,
                960,
                540,
                new ClientFrameStats(60, 120.0D, 8.3D, 12.5D, 20.0D),
                18.25D,
                45.5D,
                1.75D,
                16,
                44,
                512,
                768,
                4096,
                128,
                64,
                2,
                5,
                17,
                new ClientGpuMetrics("nvidia-smi", true, 37.0D, 1024L, 8192L, "gpus=1")
        );

        String line = ClientRuntimeLoadLog.sampleLine(snapshot);

        Assertions.assertTrue(line.contains("type=\"sample\""));
        Assertions.assertTrue(line.contains("dimension=\"minecraft:overworld\""));
        Assertions.assertTrue(line.contains("fpsEstimate=120.00"));
        Assertions.assertTrue(line.contains("processCpuLoadPct=18.25"));
        Assertions.assertTrue(line.contains("heapUsedMiB=512"));
        Assertions.assertTrue(line.contains("gpuAvailable=true"));
        Assertions.assertTrue(line.contains("gpuUtilPct=37.00"));
    }
}
