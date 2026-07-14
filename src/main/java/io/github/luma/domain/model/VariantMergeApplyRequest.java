package io.github.luma.domain.model;

public record VariantMergeApplyRequest(
        String targetProjectName,
        String sourceProjectName,
        String sourceVariantId,
        String targetVariantId,
        boolean trustedPackageConfirmed
) {

    public VariantMergeApplyRequest(
            String targetProjectName,
            String sourceProjectName,
            String sourceVariantId,
            String targetVariantId
    ) {
        this(targetProjectName, sourceProjectName, sourceVariantId, targetVariantId, false);
    }
}
