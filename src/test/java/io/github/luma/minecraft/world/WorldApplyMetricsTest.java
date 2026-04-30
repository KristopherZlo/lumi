package io.github.luma.minecraft.world;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WorldApplyMetricsTest {

    @Test
    void summarizesDirectFallbackAndPacketCounters() {
        WorldApplyMetrics metrics = new WorldApplyMetrics();

        metrics.record(BlockCommitResult.direct(4, 3, 1, 1, 3));
        metrics.record(BlockCommitResult.nativeSection(8, 7, 1, 1, 1, 7));
        metrics.record(BlockCommitResult.fallback(2, 1, 1, BlockCommitFallbackReason.CHUNK_NOT_LOADED));
        metrics.record(BlockCommitResult.blockEntityPackets(2));

        String summary = metrics.summary();

        Assertions.assertTrue(summary.contains("processedBlocks=14"));
        Assertions.assertTrue(summary.contains("changedBlocks=11"));
        Assertions.assertTrue(summary.contains("skippedBlocks=3"));
        Assertions.assertTrue(summary.contains("directSections=1"));
        Assertions.assertTrue(summary.contains("fallbackSections=1"));
        Assertions.assertTrue(summary.contains("nativeSections=1"));
        Assertions.assertTrue(summary.contains("nativeCells=7"));
        Assertions.assertTrue(summary.contains("nativeFallbackSections=0"));
        Assertions.assertTrue(summary.contains("sectionPackets=2"));
        Assertions.assertTrue(summary.contains("blockEntityPackets=3"));
        Assertions.assertTrue(summary.contains("lightChecks=10"));
        Assertions.assertTrue(summary.contains("chunk-not-loaded=1"));
    }
}
