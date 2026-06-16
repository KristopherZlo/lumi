package io.github.luma.minecraft.world;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDiagnosticsLog;
import io.github.luma.debug.LumaLoadLog;
import net.minecraft.core.BlockPos;

/**
 * Emits bounded exception summaries for unsafe world-apply elements.
 */
final class WorldApplyExceptionLogger {

    private static final WorldApplyExceptionTracker GLOBAL_TRACKER = new WorldApplyExceptionTracker(1, 64);

    private WorldApplyExceptionLogger() {
    }

    static void record(String phase, BlockPos pos, Exception exception) {
        record(phase, pos, exception, "");
    }

    static void record(String phase, BlockPos pos, Exception exception, String context) {
        WorldApplyExceptionTracker.FailureDecision decision =
                GLOBAL_TRACKER.recordFailure(phase, pos, exception);
        record(decision, context);
    }

    static void record(WorldApplyExceptionTracker.FailureDecision decision, String context) {
        if (decision == null || !decision.logDetail()) {
            return;
        }
        String detail = detail(decision, context);
        LumaMod.LOGGER.warn("Lumi skipped unsafe world-apply element: {}", detail);
        LumaLoadLog.event("world-op", "apply-exception", detail);
        if (LumaDiagnosticsLog.blockApplyEnabled()) {
            LumaDiagnosticsLog.blockApplyEvent("apply-exception", detail);
        }
    }

    private static String detail(WorldApplyExceptionTracker.FailureDecision decision, String context) {
        return "phase=" + decision.phase()
                + ", pos=" + decision.position()
                + ", exception=" + decision.exceptionClass()
                + ", message=" + sanitize(decision.message())
                + ", failureCount=" + decision.failureCount()
                + ", totalFailures=" + decision.totalFailures()
                + ", quarantined=" + decision.quarantined()
                + ", detailLimitReached=" + decision.detailLimitReached()
                + (context == null || context.isBlank() ? "" : ", " + context);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }
}
