package io.github.luma.minecraft.world;

final class WorldApplyBudgetPlanner {

    private static final int MIN_BLOCKS_PER_TICK = 128;
    private static final int MAX_BLOCKS_PER_TICK = 512;
    private static final long MIN_NANOS_PER_TICK = 1_000_000L;
    private static final long MAX_NANOS_PER_TICK = 3_000_000L;
    private static final int RESTORE_MIN_BLOCKS_PER_TICK = 1024;
    private static final int RESTORE_MAX_BLOCKS_PER_TICK = 64 * SectionChangeMask.ENTRY_COUNT;
    private static final long RESTORE_MIN_NANOS_PER_TICK = 2_000_000L;
    private static final long RESTORE_MAX_NANOS_PER_TICK = 8_000_000L;
    private static final int MIN_NATIVE_SECTIONS_PER_TICK = 1;
    private static final int MAX_NATIVE_SECTIONS_PER_TICK = 4;
    private static final int RESTORE_MIN_NATIVE_SECTIONS_PER_TICK = 16;
    private static final int RESTORE_MAX_NATIVE_SECTIONS_PER_TICK = 64;
    private static final int MAX_REWRITE_SECTIONS_PER_TICK = 1;
    private static final int RESTORE_MIN_REWRITE_SECTIONS_PER_TICK = 16;
    private static final int RESTORE_MAX_REWRITE_SECTIONS_PER_TICK = 64;

    WorldApplyBudget plan(double progressFraction, double adaptiveScale, boolean highThroughput) {
        double fraction = Math.max(0.0D, Math.min(1.0D, progressFraction));
        double scale = Math.max(0.01D, adaptiveScale);
        int minBlocks = highThroughput ? RESTORE_MIN_BLOCKS_PER_TICK : MIN_BLOCKS_PER_TICK;
        int maxBlocks = highThroughput ? RESTORE_MAX_BLOCKS_PER_TICK : MAX_BLOCKS_PER_TICK;
        long minNanos = highThroughput ? RESTORE_MIN_NANOS_PER_TICK : MIN_NANOS_PER_TICK;
        long maxNanos = highThroughput ? RESTORE_MAX_NANOS_PER_TICK : MAX_NANOS_PER_TICK;
        int minNativeSections = highThroughput
                ? RESTORE_MIN_NATIVE_SECTIONS_PER_TICK
                : MIN_NATIVE_SECTIONS_PER_TICK;
        int maxNativeSections = highThroughput
                ? RESTORE_MAX_NATIVE_SECTIONS_PER_TICK
                : MAX_NATIVE_SECTIONS_PER_TICK;
        int blocks = Math.max(1, scaledInt(minBlocks, maxBlocks, fraction, scale));
        int nativeSections = Math.max(1, scaledInt(minNativeSections, maxNativeSections, fraction, scale));
        int rewriteSections = highThroughput
                ? Math.max(1, scaledInt(
                        RESTORE_MIN_REWRITE_SECTIONS_PER_TICK,
                        RESTORE_MAX_REWRITE_SECTIONS_PER_TICK,
                        fraction,
                        scale
                ))
                : MAX_REWRITE_SECTIONS_PER_TICK;
        long nanos = Math.max(250_000L, Math.round((minNanos + ((maxNanos - minNanos) * fraction)) * scale));
        return new WorldApplyBudget(
                blocks,
                nanos,
                nativeSections,
                blocks,
                rewriteSections
        );
    }

    private static int scaledInt(int minValue, int maxValue, double fraction, double adaptiveScale) {
        return (int) Math.round((minValue + ((maxValue - minValue) * fraction)) * adaptiveScale);
    }
}
