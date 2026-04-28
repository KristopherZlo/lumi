package io.github.luma.debug;

import io.github.luma.LumaMod;

/**
 * Lightweight startup-only diagnostics guarded by a JVM flag.
 */
public final class StartupProfiler {

    private static final String FLAG = "lumi.startupProfile";
    private static final boolean ENABLED = Boolean.getBoolean(FLAG);
    private static final long STARTED_AT_NANOS = System.nanoTime();

    private StartupProfiler() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static long elapsedNanos(long startedAtNanos) {
        return ENABLED && startedAtNanos > 0L ? System.nanoTime() - startedAtNanos : 0L;
    }

    public static void logElapsed(String name, long startedAtNanos) {
        if (!ENABLED || startedAtNanos <= 0L) {
            return;
        }
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        LumaMod.LOGGER.info(
                "[startup-profile] {} elapsed={}us uptime={}ms",
                name,
                elapsedNanos / 1_000L,
                (System.nanoTime() - STARTED_AT_NANOS) / 1_000_000L
        );
    }

    public static void log(String message, Object... arguments) {
        if (!ENABLED) {
            return;
        }
        LumaMod.LOGGER.info("[startup-profile] " + message, arguments);
    }
}
