package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.PreparedChunkBatchCollapser;
import java.util.List;

/** Collapses small prepared restore batches without duplicating large payloads. */
final class RestoreBatchCollapser {

    private static final int MAX_PLACEMENTS = 1_000_000;
    private static final int MAX_NATIVE_SECTIONS = 2_048;

    private final PreparedChunkBatchCollapser collapser;

    RestoreBatchCollapser(PreparedChunkBatchCollapser collapser) {
        this.collapser = collapser;
    }

    List<PreparedChunkBatch> collapse(String source, List<PreparedChunkBatch> batches) {
        long placements = this.totalPlacements(batches);
        int nativeSections = batches.stream().mapToInt(batch -> batch.nativeSections().size()).sum();
        if (placements > MAX_PLACEMENTS || nativeSections > MAX_NATIVE_SECTIONS) {
            LumaMod.LOGGER.info(
                    "Skipping restore batch collapse for {} because prepared work is already large: batches={}, nativeSections={}, placements={}",
                    source, batches.size(), nativeSections, placements
            );
            LumaDebugLog.log(
                    "restore",
                    "Skipped restore batch collapse for {} with {} batches, {} native sections, and {} placements",
                    source, batches.size(), nativeSections, placements
            );
            return List.copyOf(batches);
        }

        try (var ignored = LumaLoadLog.measure(
                "restore", "PreparedChunkBatchCollapser.collapse",
                "source=" + source + ", batches=" + batches.size()
        )) {
            return this.collapser.collapse(batches);
        }
    }

    private long totalPlacements(List<PreparedChunkBatch> batches) {
        long total = 0L;
        for (PreparedChunkBatch batch : batches) {
            total += batch.placements().size();
            for (var section : batch.nativeSections()) {
                total += section.changedCellCount();
            }
        }
        return total;
    }
}
