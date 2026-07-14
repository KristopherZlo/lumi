package io.github.luma.domain.model;

import java.util.List;

public record VariantMergePlan(
        String sourceProjectName,
        String sourceVariantId,
        String sourceHeadVersionId,
        String targetProjectName,
        String targetVariantId,
        String targetHeadVersionId,
        String commonAncestorVersionId,
        int sourceChangedBlocks,
        int targetChangedBlocks,
        List<StoredBlockChange> mergeChanges,
        List<StoredEntityChange> mergeEntityChanges,
        HistoryPackageSafetyReport safetyReport
) {

    public VariantMergePlan {
        mergeChanges = mergeChanges == null ? List.of() : List.copyOf(mergeChanges);
        mergeEntityChanges = mergeEntityChanges == null ? List.of() : List.copyOf(mergeEntityChanges);
        safetyReport = safetyReport == null ? HistoryPackageSafetyReport.clean() : safetyReport;
    }

    public int mergeBlockCount() {
        return this.mergeChanges.size();
    }

    public int mergeEntityCount() {
        return this.mergeEntityChanges.size();
    }

    public int mergeChangeCount() {
        return this.mergeBlockCount() + this.mergeEntityCount();
    }

    public boolean canApply() {
        return this.mergeChangeCount() > 0;
    }
}
