package io.github.luma.domain.model;

import java.util.List;

public record PartialRestorePlanSummary(
        RestorePlanMode mode,
        Bounds3i bounds,
        PartialRestoreRegionSource regionSource,
        List<ChunkPoint> touchedChunks,
        String branchId,
        String baseVersionId,
        String targetVersionId,
        int changedBlocks
) {

    public PartialRestorePlanSummary {
        touchedChunks = touchedChunks == null ? List.of() : List.copyOf(touchedChunks);
        branchId = branchId == null ? "" : branchId;
        baseVersionId = baseVersionId == null ? "" : baseVersionId;
        targetVersionId = targetVersionId == null ? "" : targetVersionId;
        changedBlocks = Math.max(0, changedBlocks);
    }
}
