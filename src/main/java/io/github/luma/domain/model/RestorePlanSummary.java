package io.github.luma.domain.model;

import java.util.List;

public record RestorePlanSummary(
        RestorePlanMode mode,
        List<ChunkPoint> touchedChunks,
        String branchId,
        String baseVersionId,
        String targetVersionId
) {

    public RestorePlanSummary {
        touchedChunks = touchedChunks == null ? List.of() : List.copyOf(touchedChunks);
        branchId = branchId == null ? "" : branchId;
        baseVersionId = baseVersionId == null ? "" : baseVersionId;
        targetVersionId = targetVersionId == null ? "" : targetVersionId;
    }
}
