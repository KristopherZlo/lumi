package io.github.luma.domain.model;

import java.util.List;

public record VariantMergeApplyRequest(
        String targetProjectName,
        String sourceProjectName,
        String sourceVariantId,
        String targetVariantId,
        List<MergeConflictZoneResolution> conflictResolutions,
        boolean trustedPackageConfirmed
) {

    public VariantMergeApplyRequest(
            String targetProjectName,
            String sourceProjectName,
            String sourceVariantId,
            String targetVariantId,
            List<MergeConflictZoneResolution> conflictResolutions
    ) {
        this(targetProjectName, sourceProjectName, sourceVariantId, targetVariantId, conflictResolutions, false);
    }

    public VariantMergeApplyRequest {
        conflictResolutions = conflictResolutions == null ? List.of() : List.copyOf(conflictResolutions);
    }
}
