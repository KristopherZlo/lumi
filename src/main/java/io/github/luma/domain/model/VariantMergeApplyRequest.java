package io.github.luma.domain.model;

import java.util.List;

public record VariantMergeApplyRequest(
        String targetProjectName,
        String sourceProjectName,
        String sourceVariantId,
        String targetVariantId,
        List<MergeConflictZoneResolution> conflictResolutions
) {
}
