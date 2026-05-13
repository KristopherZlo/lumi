package io.github.luma.minecraft.world;

import io.github.luma.domain.model.OperationHandle;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldOperationMetricsReporterTest {

    @Test
    void recordsTotalDurationBeforeReturningSummary() {
        WorldApplyMetrics metrics = new WorldApplyMetrics();
        OperationHandle handle = new OperationHandle(
                "op",
                "project",
                "restore",
                Instant.parse("2026-04-28T00:00:00Z"),
                false
        );
        WorldOperationMetricsReporter reporter = new WorldOperationMetricsReporter(
                () -> Instant.parse("2026-04-28T00:00:05Z")
        );

        String summary = reporter.summary(handle, metrics);

        assertTrue(summary.contains("totalOperationDurationMs=5000"));
    }
}
