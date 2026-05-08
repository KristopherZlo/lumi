package io.github.luma.minecraft.world;

final class WorldApplyBudgetPlanner {

    private static final int MIN_BLOCKS_PER_TICK = 128;
    private static final int MAX_BLOCKS_PER_TICK = 512;
    private static final long MIN_NANOS_PER_TICK = 1_000_000L;
    private static final long MAX_NANOS_PER_TICK = 3_000_000L;
    private static final int RESTORE_MIN_BLOCKS_PER_TICK = 16_384;
    private static final int RESTORE_MAX_BLOCKS_PER_TICK = 256 * SectionChangeMask.ENTRY_COUNT;
    private static final long RESTORE_MIN_NANOS_PER_TICK = 16_000_000L;
    private static final long RESTORE_MAX_NANOS_PER_TICK = 40_000_000L;
    private static final int MIN_NATIVE_SECTIONS_PER_TICK = 1;
    private static final int MAX_NATIVE_SECTIONS_PER_TICK = 4;
    private static final int RESTORE_MIN_NATIVE_SECTIONS_PER_TICK = 32;
    private static final int RESTORE_MAX_NATIVE_SECTIONS_PER_TICK = 128;
    private static final int MAX_REWRITE_SECTIONS_PER_TICK = 1;
    private static final int RESTORE_MIN_REWRITE_SECTIONS_PER_TICK = 32;
    private static final int RESTORE_MAX_REWRITE_SECTIONS_PER_TICK = 128;
    private static final int NORMAL_MAX_DIRECT_SECTIONS_PER_TICK = 1;
    private static final int RESTORE_MIN_DIRECT_SECTIONS_PER_TICK = 64;
    private static final int RESTORE_MAX_DIRECT_SECTIONS_PER_TICK = 256;
    private static final int TURBO_MIN_DIRECT_SECTIONS_PER_TICK = 128;
    private static final int TURBO_MAX_DIRECT_SECTIONS_PER_TICK = 512;
    private static final int NORMAL_MAX_LIGHT_CHECKS_PER_TICK = 512;
    private static final int RESTORE_MAX_LIGHT_CHECKS_PER_TICK = 16_384;
    private static final int TURBO_MAX_LIGHT_CHECKS_PER_TICK = 32_768;
    private static final int NORMAL_MAX_REDSTONE_UPDATES_PER_TICK = 128;
    private static final int RESTORE_MAX_REDSTONE_UPDATES_PER_TICK = 4_096;
    private static final int TURBO_MAX_REDSTONE_UPDATES_PER_TICK = 16_384;
    private static final int NORMAL_SPARSE_STEP_CAP = 128;
    private static final int RESTORE_SPARSE_STEP_CAP = 32_768;
    private static final int TURBO_SPARSE_STEP_CAP = 131_072;
    private static final int NORMAL_MAX_PRELOAD_CHUNKS_PER_TICK = 0;
    private static final int RESTORE_MIN_PRELOAD_CHUNKS_PER_TICK = 16;
    private static final int RESTORE_MAX_PRELOAD_CHUNKS_PER_TICK = 64;
    private static final int TURBO_MIN_PRELOAD_CHUNKS_PER_TICK = 64;
    private static final int TURBO_MAX_PRELOAD_CHUNKS_PER_TICK = 128;
    private static final int TURBO_MIN_BLOCKS_PER_TICK = 65_536;
    private static final int TURBO_MAX_BLOCKS_PER_TICK = 512 * SectionChangeMask.ENTRY_COUNT;
    private static final long TURBO_MIN_NANOS_PER_TICK = 32_000_000L;
    private static final long TURBO_MAX_NANOS_PER_TICK = 64_000_000L;
    private static final int MAXIMUM_MIN_BLOCKS_PER_TICK = 256 * SectionChangeMask.ENTRY_COUNT;
    private static final int MAXIMUM_MAX_BLOCKS_PER_TICK = 1024 * SectionChangeMask.ENTRY_COUNT;
    private static final long MAXIMUM_MIN_NANOS_PER_TICK = 80_000_000L;
    private static final long MAXIMUM_MAX_NANOS_PER_TICK = 200_000_000L;
    private static final int MAXIMUM_MIN_NATIVE_SECTIONS_PER_TICK = 256;
    private static final int MAXIMUM_MAX_NATIVE_SECTIONS_PER_TICK = 1024;
    private static final int MAXIMUM_MIN_REWRITE_SECTIONS_PER_TICK = 256;
    private static final int MAXIMUM_MAX_REWRITE_SECTIONS_PER_TICK = 1024;
    private static final int MAXIMUM_MIN_DIRECT_SECTIONS_PER_TICK = 512;
    private static final int MAXIMUM_MAX_DIRECT_SECTIONS_PER_TICK = 2048;
    private static final int MAXIMUM_MAX_LIGHT_CHECKS_PER_TICK = 131_072;
    private static final int MAXIMUM_MAX_REDSTONE_UPDATES_PER_TICK = 65_536;
    private static final int MAXIMUM_SPARSE_STEP_CAP = 524_288;
    private static final int MAXIMUM_MIN_PRELOAD_CHUNKS_PER_TICK = 256;
    private static final int MAXIMUM_MAX_PRELOAD_CHUNKS_PER_TICK = 512;
    private static final int NORMAL_MAX_SYNC_CHUNK_LOADS_PER_TICK = 0;
    private static final int RESTORE_MAX_SYNC_CHUNK_LOADS_PER_TICK = 32;
    private static final int TURBO_MAX_SYNC_CHUNK_LOADS_PER_TICK = 128;
    private static final int MAXIMUM_MAX_SYNC_CHUNK_LOADS_PER_TICK = 512;
    private static final int NORMAL_MAX_BLOCK_ENTITIES_PER_TICK = 64;
    private static final int RESTORE_MAX_BLOCK_ENTITIES_PER_TICK = 256;
    private static final int TURBO_MAX_BLOCK_ENTITIES_PER_TICK = 512;
    private static final int MAXIMUM_MAX_BLOCK_ENTITIES_PER_TICK = 2048;
    private static final int NORMAL_MAX_ENTITY_OPERATIONS_PER_TICK = 32;
    private static final int RESTORE_MAX_ENTITY_OPERATIONS_PER_TICK = 128;
    private static final int TURBO_MAX_ENTITY_OPERATIONS_PER_TICK = 256;
    private static final int MAXIMUM_MAX_ENTITY_OPERATIONS_PER_TICK = 1024;

    WorldApplyBudget plan(double progressFraction, double adaptiveScale, WorldApplyProfile profile) {
        WorldApplyProfile resolvedProfile = profile == null ? WorldApplyProfile.NORMAL : profile;
        double fraction = Math.max(0.0D, Math.min(1.0D, progressFraction));
        double scale = Math.max(0.01D, adaptiveScale);
        int minBlocks = switch (resolvedProfile) {
            case NORMAL -> MIN_BLOCKS_PER_TICK;
            case HISTORY_FAST -> RESTORE_MIN_BLOCKS_PER_TICK;
            case DIAGNOSTIC_TURBO -> TURBO_MIN_BLOCKS_PER_TICK;
            case MAXIMUM -> MAXIMUM_MIN_BLOCKS_PER_TICK;
        };
        int maxBlocks = switch (resolvedProfile) {
            case NORMAL -> MAX_BLOCKS_PER_TICK;
            case HISTORY_FAST -> RESTORE_MAX_BLOCKS_PER_TICK;
            case DIAGNOSTIC_TURBO -> TURBO_MAX_BLOCKS_PER_TICK;
            case MAXIMUM -> MAXIMUM_MAX_BLOCKS_PER_TICK;
        };
        long minNanos = switch (resolvedProfile) {
            case NORMAL -> MIN_NANOS_PER_TICK;
            case HISTORY_FAST -> RESTORE_MIN_NANOS_PER_TICK;
            case DIAGNOSTIC_TURBO -> TURBO_MIN_NANOS_PER_TICK;
            case MAXIMUM -> MAXIMUM_MIN_NANOS_PER_TICK;
        };
        long maxNanos = switch (resolvedProfile) {
            case NORMAL -> MAX_NANOS_PER_TICK;
            case HISTORY_FAST -> RESTORE_MAX_NANOS_PER_TICK;
            case DIAGNOSTIC_TURBO -> TURBO_MAX_NANOS_PER_TICK;
            case MAXIMUM -> MAXIMUM_MAX_NANOS_PER_TICK;
        };
        int minNativeSections = switch (resolvedProfile) {
            case NORMAL -> MIN_NATIVE_SECTIONS_PER_TICK;
            case HISTORY_FAST, DIAGNOSTIC_TURBO -> RESTORE_MIN_NATIVE_SECTIONS_PER_TICK;
            case MAXIMUM -> MAXIMUM_MIN_NATIVE_SECTIONS_PER_TICK;
        };
        int maxNativeSections = switch (resolvedProfile) {
            case NORMAL -> MAX_NATIVE_SECTIONS_PER_TICK;
            case HISTORY_FAST, DIAGNOSTIC_TURBO -> RESTORE_MAX_NATIVE_SECTIONS_PER_TICK;
            case MAXIMUM -> MAXIMUM_MAX_NATIVE_SECTIONS_PER_TICK;
        };
        int blocks = Math.max(1, scaledInt(minBlocks, maxBlocks, fraction, scale));
        int nativeSections = Math.max(1, scaledInt(minNativeSections, maxNativeSections, fraction, scale));
        int rewriteSections = switch (resolvedProfile) {
            case NORMAL -> MAX_REWRITE_SECTIONS_PER_TICK;
            case HISTORY_FAST, DIAGNOSTIC_TURBO -> Math.max(1, scaledInt(
                    RESTORE_MIN_REWRITE_SECTIONS_PER_TICK,
                    RESTORE_MAX_REWRITE_SECTIONS_PER_TICK,
                    fraction,
                    scale
            ));
            case MAXIMUM -> Math.max(1, scaledInt(
                    MAXIMUM_MIN_REWRITE_SECTIONS_PER_TICK,
                    MAXIMUM_MAX_REWRITE_SECTIONS_PER_TICK,
                    fraction,
                    scale
            ));
        };
        int directSections = switch (resolvedProfile) {
            case NORMAL -> NORMAL_MAX_DIRECT_SECTIONS_PER_TICK;
            case HISTORY_FAST -> Math.max(
                    RESTORE_MIN_DIRECT_SECTIONS_PER_TICK,
                    scaledInt(RESTORE_MIN_DIRECT_SECTIONS_PER_TICK, RESTORE_MAX_DIRECT_SECTIONS_PER_TICK, fraction, scale)
            );
            case DIAGNOSTIC_TURBO -> Math.max(
                    TURBO_MIN_DIRECT_SECTIONS_PER_TICK,
                    scaledInt(TURBO_MIN_DIRECT_SECTIONS_PER_TICK, TURBO_MAX_DIRECT_SECTIONS_PER_TICK, fraction, scale)
            );
            case MAXIMUM -> Math.max(
                    MAXIMUM_MIN_DIRECT_SECTIONS_PER_TICK,
                    scaledInt(MAXIMUM_MIN_DIRECT_SECTIONS_PER_TICK, MAXIMUM_MAX_DIRECT_SECTIONS_PER_TICK, fraction, scale)
            );
        };
        int lightChecks = switch (resolvedProfile) {
            case NORMAL -> Math.max(128, Math.min(NORMAL_MAX_LIGHT_CHECKS_PER_TICK, blocks));
            case HISTORY_FAST -> Math.max(4096, scaledInt(4096, RESTORE_MAX_LIGHT_CHECKS_PER_TICK, fraction, scale));
            case DIAGNOSTIC_TURBO -> Math.max(8192, scaledInt(8192, TURBO_MAX_LIGHT_CHECKS_PER_TICK, fraction, scale));
            case MAXIMUM -> Math.max(32_768, scaledInt(32_768, MAXIMUM_MAX_LIGHT_CHECKS_PER_TICK, fraction, scale));
        };
        int redstoneUpdates = switch (resolvedProfile) {
            case NORMAL -> Math.max(32, Math.min(NORMAL_MAX_REDSTONE_UPDATES_PER_TICK, blocks));
            case HISTORY_FAST -> Math.max(256, scaledInt(256, RESTORE_MAX_REDSTONE_UPDATES_PER_TICK, fraction, scale));
            case DIAGNOSTIC_TURBO -> Math.max(512, scaledInt(512, TURBO_MAX_REDSTONE_UPDATES_PER_TICK, fraction, scale));
            case MAXIMUM -> Math.max(2048, scaledInt(2048, MAXIMUM_MAX_REDSTONE_UPDATES_PER_TICK, fraction, scale));
        };
        int sparseStepCap = switch (resolvedProfile) {
            case NORMAL -> NORMAL_SPARSE_STEP_CAP;
            case HISTORY_FAST -> RESTORE_SPARSE_STEP_CAP;
            case DIAGNOSTIC_TURBO -> TURBO_SPARSE_STEP_CAP;
            case MAXIMUM -> MAXIMUM_SPARSE_STEP_CAP;
        };
        int preloadChunks = switch (resolvedProfile) {
            case NORMAL -> NORMAL_MAX_PRELOAD_CHUNKS_PER_TICK;
            case HISTORY_FAST -> Math.max(1, scaledInt(
                    RESTORE_MIN_PRELOAD_CHUNKS_PER_TICK,
                    RESTORE_MAX_PRELOAD_CHUNKS_PER_TICK,
                    fraction,
                    scale
            ));
            case DIAGNOSTIC_TURBO -> Math.max(1, scaledInt(
                    TURBO_MIN_PRELOAD_CHUNKS_PER_TICK,
                    TURBO_MAX_PRELOAD_CHUNKS_PER_TICK,
                    fraction,
                    scale
            ));
            case MAXIMUM -> Math.max(1, scaledInt(
                    MAXIMUM_MIN_PRELOAD_CHUNKS_PER_TICK,
                    MAXIMUM_MAX_PRELOAD_CHUNKS_PER_TICK,
                    fraction,
                    scale
            ));
        };
        int syncChunkLoads = switch (resolvedProfile) {
            case NORMAL -> NORMAL_MAX_SYNC_CHUNK_LOADS_PER_TICK;
            case HISTORY_FAST -> RESTORE_MAX_SYNC_CHUNK_LOADS_PER_TICK;
            case DIAGNOSTIC_TURBO -> TURBO_MAX_SYNC_CHUNK_LOADS_PER_TICK;
            case MAXIMUM -> MAXIMUM_MAX_SYNC_CHUNK_LOADS_PER_TICK;
        };
        int blockEntities = switch (resolvedProfile) {
            case NORMAL -> NORMAL_MAX_BLOCK_ENTITIES_PER_TICK;
            case HISTORY_FAST -> RESTORE_MAX_BLOCK_ENTITIES_PER_TICK;
            case DIAGNOSTIC_TURBO -> TURBO_MAX_BLOCK_ENTITIES_PER_TICK;
            case MAXIMUM -> MAXIMUM_MAX_BLOCK_ENTITIES_PER_TICK;
        };
        int entityOperations = switch (resolvedProfile) {
            case NORMAL -> NORMAL_MAX_ENTITY_OPERATIONS_PER_TICK;
            case HISTORY_FAST -> RESTORE_MAX_ENTITY_OPERATIONS_PER_TICK;
            case DIAGNOSTIC_TURBO -> TURBO_MAX_ENTITY_OPERATIONS_PER_TICK;
            case MAXIMUM -> MAXIMUM_MAX_ENTITY_OPERATIONS_PER_TICK;
        };
        sparseStepCap = Math.max(1, Math.min(sparseStepCap, blocks));
        lightChecks = Math.max(1, lightChecks);
        redstoneUpdates = Math.max(1, redstoneUpdates);
        long minimumProfileNanos = switch (resolvedProfile) {
            case NORMAL -> 250_000L;
            case HISTORY_FAST -> RESTORE_MIN_NANOS_PER_TICK;
            case DIAGNOSTIC_TURBO -> TURBO_MIN_NANOS_PER_TICK;
            case MAXIMUM -> MAXIMUM_MIN_NANOS_PER_TICK;
        };
        long nanos = Math.max(
                minimumProfileNanos,
                Math.round((minNanos + ((maxNanos - minNanos) * fraction)) * scale)
        );
        return new WorldApplyBudget(
                blocks,
                nanos,
                nativeSections,
                blocks,
                rewriteSections,
                directSections,
                lightChecks,
                redstoneUpdates,
                sparseStepCap,
                preloadChunks,
                syncChunkLoads,
                blockEntities,
                entityOperations
        );
    }

    WorldApplyBudget plan(double progressFraction, double adaptiveScale, boolean highThroughput) {
        return this.plan(
                progressFraction,
                adaptiveScale,
                highThroughput ? WorldApplyProfile.HISTORY_FAST : WorldApplyProfile.NORMAL
        );
    }

    private static int scaledInt(int minValue, int maxValue, double fraction, double adaptiveScale) {
        return (int) Math.round((minValue + ((maxValue - minValue) * fraction)) * adaptiveScale);
    }
}
