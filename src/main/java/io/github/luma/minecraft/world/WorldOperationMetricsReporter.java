package io.github.luma.minecraft.world;

import io.github.luma.domain.model.OperationHandle;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Produces final operation metric summaries with consistent total duration.
 */
final class WorldOperationMetricsReporter {

    private final Supplier<Instant> clock;

    WorldOperationMetricsReporter() {
        this(Instant::now);
    }

    WorldOperationMetricsReporter(Supplier<Instant> clock) {
        this.clock = clock == null ? Instant::now : clock;
    }

    String summary(OperationHandle handle, WorldApplyMetrics metrics) {
        if (metrics == null) {
            return "";
        }
        if (handle != null && handle.startedAt() != null) {
            metrics.recordTotalDuration(Duration.between(handle.startedAt(), this.clock.get()).toNanos());
        }
        return metrics.summary();
    }
}
