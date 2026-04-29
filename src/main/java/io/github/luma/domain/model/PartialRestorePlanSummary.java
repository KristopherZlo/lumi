package io.github.luma.domain.model;

import java.util.List;

public record PartialRestorePlanSummary(
        RestorePlanMode mode,
        Bounds3i bounds,
        PartialRestoreMode partialRestoreMode,
        PartialRestoreRegionSource regionSource,
        List<ChunkPoint> touchedChunks,
        String branchId,
        String baseVersionId,
        String targetVersionId,
        int changedBlocks
) {

    public PartialRestorePlanSummary {
        partialRestoreMode = partialRestoreMode == null ? PartialRestoreMode.SELECTED_AREA : partialRestoreMode;
        touchedChunks = touchedChunks == null ? List.of() : List.copyOf(touchedChunks);
        branchId = branchId == null ? "" : branchId;
        baseVersionId = baseVersionId == null ? "" : baseVersionId;
        targetVersionId = targetVersionId == null ? "" : targetVersionId;
        changedBlocks = Math.max(0, changedBlocks);
    }

    public PartialRestorePlanSummary(
            RestorePlanMode mode,
            Bounds3i bounds,
            PartialRestoreRegionSource regionSource,
            List<ChunkPoint> touchedChunks,
            String branchId,
            String baseVersionId,
            String targetVersionId,
            int changedBlocks
    ) {
        this(
                mode,
                bounds,
                PartialRestoreMode.SELECTED_AREA,
                regionSource,
                touchedChunks,
                branchId,
                baseVersionId,
                targetVersionId,
                changedBlocks
        );
    }
}
