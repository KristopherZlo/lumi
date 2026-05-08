package io.github.luma.debug;

import java.nio.file.Path;

/**
 * Focused diagnostic logs for runtime surfaces that need more detail than the
 * aggregate load log.
 */
public final class LumaDiagnosticsLog {

    private static final StructuredDiagnosticsLog LIGHT_LOG = new StructuredDiagnosticsLog(
            "light",
            "lumi.lightLog",
            "lumi.lightLog.path",
            "logs/lumi-light.log"
    );
    private static final StructuredDiagnosticsLog BLOCK_APPLY_LOG = new StructuredDiagnosticsLog(
            "block-apply",
            "lumi.blockApplyLog",
            "lumi.blockApplyLog.path",
            "logs/lumi-block-apply.log"
    );

    private LumaDiagnosticsLog() {
    }

    public static boolean lightEnabled() {
        return LIGHT_LOG.enabled();
    }

    public static Path lightPath() {
        return LIGHT_LOG.configuredPath();
    }

    public static void lightEvent(String name, String detail) {
        LIGHT_LOG.event("light", name, detail);
    }

    public static void lightSpan(String name, long elapsedNanos, String detail) {
        LIGHT_LOG.span("light", name, elapsedNanos, detail);
    }

    public static boolean blockApplyEnabled() {
        return BLOCK_APPLY_LOG.enabled();
    }

    public static Path blockApplyPath() {
        return BLOCK_APPLY_LOG.configuredPath();
    }

    public static void blockApplyEvent(String name, String detail) {
        BLOCK_APPLY_LOG.event("block-apply", name, detail);
    }

    public static void blockApplySpan(String name, long elapsedNanos, String detail) {
        BLOCK_APPLY_LOG.span("block-apply", name, elapsedNanos, detail);
    }

    public static void close() {
        LIGHT_LOG.close();
        BLOCK_APPLY_LOG.close();
    }
}
