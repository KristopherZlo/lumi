package io.github.luma.minecraft.debug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.WorldMutationSource;
import java.time.Instant;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class CaptureSkipLogThrottlerTest {

    private final CaptureSkipLogThrottler throttler = new CaptureSkipLogThrottler();

    @Test
    void logsInitialSamplesThenPeriodicSummaries() {
        BuildProject project = BuildProject.createWorldWorkspace("Test", "minecraft:overworld", Instant.EPOCH);

        for (int i = 0; i < CaptureSkipLogThrottler.SAMPLE_LIMIT; i++) {
            CaptureSkipLogThrottler.Decision decision = this.record(project);
            assertTrue(decision.logSample());
            assertFalse(decision.logSummary());
        }

        CaptureSkipLogThrottler.Decision suppressed = this.record(project);
        assertFalse(suppressed.shouldLog());

        CaptureSkipLogThrottler.Decision summary = null;
        for (int i = 1; i < CaptureSkipLogThrottler.SUMMARY_INTERVAL; i++) {
            summary = this.record(project);
        }
        assertTrue(summary.logSummary());
        assertFalse(summary.logSample());
    }

    @Test
    void keepsIndependentBucketsPerProjectSourceAndReason() {
        BuildProject project = BuildProject.createWorldWorkspace("Test", "minecraft:overworld", Instant.EPOCH);
        for (int i = 0; i < CaptureSkipLogThrottler.SAMPLE_LIMIT; i++) {
            this.record(project);
        }
        assertFalse(this.record(project).shouldLog());

        assertTrue(this.throttler.record(
                project,
                WorldMutationSource.GROWTH,
                "different-reason",
                BlockPos.ZERO
        ).logSample());
        assertTrue(this.throttler.record(
                project,
                WorldMutationSource.BLOCK_UPDATE,
                "no-active-session-source-cannot-bootstrap",
                BlockPos.ZERO
        ).logSample());
        assertTrue(this.record(BuildProject.createWorldWorkspace("Other", "minecraft:overworld", Instant.EPOCH)).logSample());
    }

    private CaptureSkipLogThrottler.Decision record(BuildProject project) {
        return this.throttler.record(
                project,
                WorldMutationSource.GROWTH,
                "no-active-session-source-cannot-bootstrap",
                BlockPos.ZERO
        );
    }
}
