package io.github.luma.minecraft.world;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WorldApplyMetricsTest {

    @Test
    void summarizesDirectFallbackAndPacketCounters() {
        WorldApplyMetrics metrics = new WorldApplyMetrics();

        metrics.record(BlockCommitResult.direct(4, 3, 1, 1, 3));
        metrics.record(BlockCommitResult.rewriteSection(16, 15, 1, 1, 2));
        metrics.record(BlockCommitResult.nativeSection(8, 7, 1, 1, 1, 7));
        metrics.record(BlockCommitResult.rewriteFallback(4, 0, 4, BlockCommitFallbackReason.REWRITE_UNAVAILABLE));
        metrics.record(BlockCommitResult.fallback(2, 1, 1, BlockCommitFallbackReason.CHUNK_NOT_LOADED));
        metrics.record(BlockCommitResult.blockEntityPackets(2));
        metrics.recordApplyTick(18);
        metrics.recordApplyTick(0);
        metrics.recordLightDrainTick(2_500_000L);

        String summary = metrics.summary();

        Assertions.assertTrue(summary.contains("processedBlocks=34"));
        Assertions.assertTrue(summary.contains("changedBlocks=26"));
        Assertions.assertTrue(summary.contains("skippedBlocks=8"));
        Assertions.assertTrue(summary.contains("directSections=1"));
        Assertions.assertTrue(summary.contains("fallbackSections=1"));
        Assertions.assertTrue(summary.contains("rewriteSections=1"));
        Assertions.assertTrue(summary.contains("rewriteCells=15"));
        Assertions.assertTrue(summary.contains("rewriteFallbackSections=1"));
        Assertions.assertTrue(summary.contains("nativeSections=1"));
        Assertions.assertTrue(summary.contains("nativeCells=7"));
        Assertions.assertTrue(summary.contains("nativeFallbackSections=0"));
        Assertions.assertTrue(summary.contains("sectionPackets=3"));
        Assertions.assertTrue(summary.contains("blockEntityPackets=3"));
        Assertions.assertTrue(summary.contains("lightChecks=12"));
        Assertions.assertTrue(summary.contains("applyTicks=2"));
        Assertions.assertTrue(summary.contains("workTicks=1"));
        Assertions.assertTrue(summary.contains("avgWorkPerTick=34"));
        Assertions.assertTrue(summary.contains("maxWorkPerTick=18"));
        Assertions.assertTrue(summary.contains("lightDrainTicks=1"));
        Assertions.assertTrue(summary.contains("lightDrainDurationMs=2"));
        Assertions.assertTrue(summary.contains("chunk-not-loaded=1"));
        Assertions.assertTrue(summary.contains("rewrite-unavailable=1"));
    }

    @Test
    void recordsChunkDirectSectionsAsOneCommit() {
        WorldApplyMetrics metrics = new WorldApplyMetrics();

        metrics.record(BlockCommitResult.direct(183, 180, 3, 4, 128, 3));

        String summary = metrics.summary();

        Assertions.assertTrue(summary.contains("processedBlocks=183"));
        Assertions.assertTrue(summary.contains("directSections=3"));
        Assertions.assertTrue(summary.contains("sectionPackets=4"));
        Assertions.assertTrue(summary.contains("lightChecks=128"));
    }
}
