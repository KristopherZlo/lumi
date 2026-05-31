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
        int changedBlocks,
        int changedEntities
) {

    public PartialRestorePlanSummary {
        partialRestoreMode = partialRestoreMode == null ? PartialRestoreMode.SELECTED_AREA : partialRestoreMode;
        touchedChunks = touchedChunks == null ? List.of() : List.copyOf(touchedChunks);
        branchId = branchId == null ? "" : branchId;
        baseVersionId = baseVersionId == null ? "" : baseVersionId;
        targetVersionId = targetVersionId == null ? "" : targetVersionId;
        changedBlocks = Math.max(0, changedBlocks);
        changedEntities = Math.max(0, changedEntities);
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
                changedBlocks,
                0
        );
    }

    public PartialRestorePlanSummary(
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
        this(
                mode,
                bounds,
                partialRestoreMode,
                regionSource,
                touchedChunks,
                branchId,
                baseVersionId,
                targetVersionId,
                changedBlocks,
                0
        );
    }

    public int totalChanges() {
        return this.changedBlocks + this.changedEntities;
    }

    public boolean hasChanges() {
        return this.totalChanges() > 0;
    }
}
