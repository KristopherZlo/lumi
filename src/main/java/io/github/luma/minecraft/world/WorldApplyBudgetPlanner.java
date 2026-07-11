package io.github.luma.minecraft.world;

import io.github.luma.domain.model.SectionChangeMask;

import java.util.EnumMap;
import java.util.Map;

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
    private static final int RESTORE_MAX_SYNC_CHUNK_LOADS_PER_TICK = 1;
    private static final int TURBO_MAX_SYNC_CHUNK_LOADS_PER_TICK = 0;
    private static final int MAXIMUM_MAX_SYNC_CHUNK_LOADS_PER_TICK = 0;
    private static final int NORMAL_MAX_BLOCK_ENTITIES_PER_TICK = 64;
    private static final int RESTORE_MAX_BLOCK_ENTITIES_PER_TICK = 256;
    private static final int TURBO_MAX_BLOCK_ENTITIES_PER_TICK = 512;
    private static final int MAXIMUM_MAX_BLOCK_ENTITIES_PER_TICK = 2048;
    private static final int NORMAL_MAX_ENTITY_OPERATIONS_PER_TICK = 32;
    private static final int RESTORE_MAX_ENTITY_OPERATIONS_PER_TICK = 128;
    private static final int TURBO_MAX_ENTITY_OPERATIONS_PER_TICK = 256;
    private static final int MAXIMUM_MAX_ENTITY_OPERATIONS_PER_TICK = 1024;

    private static final Map<WorldApplyProfile, BudgetSpec> SPECS = specs();

    WorldApplyBudget plan(double progressFraction, double adaptiveScale, WorldApplyProfile profile) {
        BudgetSpec spec = SPECS.get(profile == null ? WorldApplyProfile.NORMAL : profile);
        double fraction = Math.max(0.0D, Math.min(1.0D, progressFraction));
        double scale = Math.max(0.01D, adaptiveScale);
        int blocks = Math.max(1, spec.blocks().scaled(fraction, scale));
        int nativeSections = Math.max(1, spec.nativeSections().scaled(fraction, scale));
        int rewriteSections = spec.rewriteSections().scaled(fraction, scale);
        int directSections = spec.directSections().scaled(fraction, scale);
        int lightChecks = spec.lightChecks().scaled(fraction, scale, blocks);
        int redstoneUpdates = spec.redstoneUpdates().scaled(fraction, scale, blocks);
        int sparseStepCap = Math.max(1, Math.min(spec.sparseStepCap(), blocks));
        long nanos = Math.max(
                spec.minimumProfileNanos(),
                Math.round((spec.minNanos() + ((spec.maxNanos() - spec.minNanos()) * fraction)) * scale)
        );

        return new WorldApplyBudget(
                blocks,
                nanos,
                nativeSections,
                blocks,
                rewriteSections,
                directSections,
                Math.max(1, lightChecks),
                Math.max(1, redstoneUpdates),
                sparseStepCap,
                spec.preloadChunks().scaled(fraction, scale),
                spec.maxSyncChunkLoads(),
                spec.maxBlockEntities(),
                spec.maxEntityOperations()
        );
    }

    WorldApplyBudget plan(double progressFraction, double adaptiveScale, boolean highThroughput) {
        return this.plan(
                progressFraction,
                adaptiveScale,
                highThroughput ? WorldApplyProfile.HISTORY_FAST : WorldApplyProfile.NORMAL
        );
    }

    private static Map<WorldApplyProfile, BudgetSpec> specs() {
        EnumMap<WorldApplyProfile, BudgetSpec> specs = new EnumMap<>(WorldApplyProfile.class);
        specs.put(WorldApplyProfile.NORMAL, new BudgetSpec(
                IntBudgetRange.scaled(MIN_BLOCKS_PER_TICK, MAX_BLOCKS_PER_TICK, 1),
                MIN_NANOS_PER_TICK,
                MAX_NANOS_PER_TICK,
                250_000L,
                IntBudgetRange.scaled(MIN_NATIVE_SECTIONS_PER_TICK, MAX_NATIVE_SECTIONS_PER_TICK, 1),
                IntBudgetRange.scaled(MAX_REWRITE_SECTIONS_PER_TICK, MAX_REWRITE_SECTIONS_PER_TICK, 1),
                IntBudgetRange.scaled(NORMAL_MAX_DIRECT_SECTIONS_PER_TICK, NORMAL_MAX_DIRECT_SECTIONS_PER_TICK, 1),
                IntBudgetRange.blockLimited(128, NORMAL_MAX_LIGHT_CHECKS_PER_TICK),
                IntBudgetRange.blockLimited(32, NORMAL_MAX_REDSTONE_UPDATES_PER_TICK),
                NORMAL_SPARSE_STEP_CAP,
                IntBudgetRange.scaled(NORMAL_MAX_PRELOAD_CHUNKS_PER_TICK, NORMAL_MAX_PRELOAD_CHUNKS_PER_TICK, 0),
                NORMAL_MAX_SYNC_CHUNK_LOADS_PER_TICK,
                NORMAL_MAX_BLOCK_ENTITIES_PER_TICK,
                NORMAL_MAX_ENTITY_OPERATIONS_PER_TICK
        ));
        specs.put(WorldApplyProfile.HISTORY_FAST, new BudgetSpec(
                IntBudgetRange.scaled(RESTORE_MIN_BLOCKS_PER_TICK, RESTORE_MAX_BLOCKS_PER_TICK, 1),
                RESTORE_MIN_NANOS_PER_TICK,
                RESTORE_MAX_NANOS_PER_TICK,
                RESTORE_MIN_NANOS_PER_TICK,
                IntBudgetRange.scaled(RESTORE_MIN_NATIVE_SECTIONS_PER_TICK, RESTORE_MAX_NATIVE_SECTIONS_PER_TICK, 1),
                IntBudgetRange.scaled(RESTORE_MIN_REWRITE_SECTIONS_PER_TICK, RESTORE_MAX_REWRITE_SECTIONS_PER_TICK, 1),
                IntBudgetRange.scaled(RESTORE_MIN_DIRECT_SECTIONS_PER_TICK, RESTORE_MAX_DIRECT_SECTIONS_PER_TICK, RESTORE_MIN_DIRECT_SECTIONS_PER_TICK),
                IntBudgetRange.scaled(4096, RESTORE_MAX_LIGHT_CHECKS_PER_TICK, 4096),
                IntBudgetRange.scaled(256, RESTORE_MAX_REDSTONE_UPDATES_PER_TICK, 256),
                RESTORE_SPARSE_STEP_CAP,
                IntBudgetRange.scaled(RESTORE_MIN_PRELOAD_CHUNKS_PER_TICK, RESTORE_MAX_PRELOAD_CHUNKS_PER_TICK, 1),
                RESTORE_MAX_SYNC_CHUNK_LOADS_PER_TICK,
                RESTORE_MAX_BLOCK_ENTITIES_PER_TICK,
                RESTORE_MAX_ENTITY_OPERATIONS_PER_TICK
        ));
        specs.put(WorldApplyProfile.DIAGNOSTIC_TURBO, new BudgetSpec(
                IntBudgetRange.scaled(TURBO_MIN_BLOCKS_PER_TICK, TURBO_MAX_BLOCKS_PER_TICK, 1),
                TURBO_MIN_NANOS_PER_TICK,
                TURBO_MAX_NANOS_PER_TICK,
                TURBO_MIN_NANOS_PER_TICK,
                IntBudgetRange.scaled(RESTORE_MIN_NATIVE_SECTIONS_PER_TICK, RESTORE_MAX_NATIVE_SECTIONS_PER_TICK, 1),
                IntBudgetRange.scaled(RESTORE_MIN_REWRITE_SECTIONS_PER_TICK, RESTORE_MAX_REWRITE_SECTIONS_PER_TICK, 1),
                IntBudgetRange.scaled(TURBO_MIN_DIRECT_SECTIONS_PER_TICK, TURBO_MAX_DIRECT_SECTIONS_PER_TICK, TURBO_MIN_DIRECT_SECTIONS_PER_TICK),
                IntBudgetRange.scaled(8192, TURBO_MAX_LIGHT_CHECKS_PER_TICK, 8192),
                IntBudgetRange.scaled(512, TURBO_MAX_REDSTONE_UPDATES_PER_TICK, 512),
                TURBO_SPARSE_STEP_CAP,
                IntBudgetRange.scaled(TURBO_MIN_PRELOAD_CHUNKS_PER_TICK, TURBO_MAX_PRELOAD_CHUNKS_PER_TICK, 1),
                TURBO_MAX_SYNC_CHUNK_LOADS_PER_TICK,
                TURBO_MAX_BLOCK_ENTITIES_PER_TICK,
                TURBO_MAX_ENTITY_OPERATIONS_PER_TICK
        ));
        specs.put(WorldApplyProfile.MAXIMUM, new BudgetSpec(
                IntBudgetRange.scaled(MAXIMUM_MIN_BLOCKS_PER_TICK, MAXIMUM_MAX_BLOCKS_PER_TICK, 1),
                MAXIMUM_MIN_NANOS_PER_TICK,
                MAXIMUM_MAX_NANOS_PER_TICK,
                RESTORE_MIN_NANOS_PER_TICK,
                IntBudgetRange.scaled(MAXIMUM_MIN_NATIVE_SECTIONS_PER_TICK, MAXIMUM_MAX_NATIVE_SECTIONS_PER_TICK, 1),
                IntBudgetRange.scaled(MAXIMUM_MIN_REWRITE_SECTIONS_PER_TICK, MAXIMUM_MAX_REWRITE_SECTIONS_PER_TICK, 1),
                IntBudgetRange.scaled(MAXIMUM_MIN_DIRECT_SECTIONS_PER_TICK, MAXIMUM_MAX_DIRECT_SECTIONS_PER_TICK, MAXIMUM_MIN_DIRECT_SECTIONS_PER_TICK),
                IntBudgetRange.scaled(32_768, MAXIMUM_MAX_LIGHT_CHECKS_PER_TICK, 32_768),
                IntBudgetRange.scaled(2048, MAXIMUM_MAX_REDSTONE_UPDATES_PER_TICK, 2048),
                MAXIMUM_SPARSE_STEP_CAP,
                IntBudgetRange.scaled(MAXIMUM_MIN_PRELOAD_CHUNKS_PER_TICK, MAXIMUM_MAX_PRELOAD_CHUNKS_PER_TICK, 1),
                MAXIMUM_MAX_SYNC_CHUNK_LOADS_PER_TICK,
                MAXIMUM_MAX_BLOCK_ENTITIES_PER_TICK,
                MAXIMUM_MAX_ENTITY_OPERATIONS_PER_TICK
        ));
        return specs;
    }

    private static int scaledInt(int minValue, int maxValue, double fraction, double adaptiveScale) {
        return (int) Math.round((minValue + ((maxValue - minValue) * fraction)) * adaptiveScale);
    }

    private record BudgetSpec(
            IntBudgetRange blocks,
            long minNanos,
            long maxNanos,
            long minimumProfileNanos,
            IntBudgetRange nativeSections,
            IntBudgetRange rewriteSections,
            IntBudgetRange directSections,
            IntBudgetRange lightChecks,
            IntBudgetRange redstoneUpdates,
            int sparseStepCap,
            IntBudgetRange preloadChunks,
            int maxSyncChunkLoads,
            int maxBlockEntities,
            int maxEntityOperations
    ) {
    }

    private record IntBudgetRange(int minValue, int maxValue, int floor, boolean blockLimited) {

        static IntBudgetRange scaled(int minValue, int maxValue, int floor) {
            return new IntBudgetRange(minValue, maxValue, floor, false);
        }

        static IntBudgetRange blockLimited(int floor, int maxValue) {
            return new IntBudgetRange(floor, maxValue, floor, true);
        }

        int scaled(double fraction, double adaptiveScale) {
            return Math.max(this.floor, scaledInt(this.minValue, this.maxValue, fraction, adaptiveScale));
        }

        int scaled(double fraction, double adaptiveScale, int blocks) {
            if (this.blockLimited) {
                return Math.max(this.floor, Math.min(this.maxValue, blocks));
            }
            return this.scaled(fraction, adaptiveScale);
        }
    }
}
