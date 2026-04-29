package io.github.luma.minecraft.world;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WorldApplyMetricsTest {

    @Test
    void summarizesDirectFallbackAndPacketCounters() {
        WorldApplyMetrics metrics = new WorldApplyMetrics();

        metrics.record(BlockCommitResult.direct(4, 3, 1, 1, 3));
        metrics.record(BlockCommitResult.fallback(2, 1, 1, BlockCommitFallbackReason.CHUNK_NOT_LOADED));
        metrics.record(BlockCommitResult.blockEntityPackets(2));

        String summary = metrics.summary();

        Assertions.assertTrue(summary.contains("processedBlocks=6"));
        Assertions.assertTrue(summary.contains("changedBlocks=4"));
        Assertions.assertTrue(summary.contains("skippedBlocks=2"));
        Assertions.assertTrue(summary.contains("directSections=1"));
        Assertions.assertTrue(summary.contains("fallbackSections=1"));
        Assertions.assertTrue(summary.contains("sectionPackets=1"));
        Assertions.assertTrue(summary.contains("blockEntityPackets=2"));
        Assertions.assertTrue(summary.contains("lightChecks=3"));
        Assertions.assertTrue(summary.contains("chunk-not-loaded=1"));
    }
}
