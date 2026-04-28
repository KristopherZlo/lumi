package io.github.luma.domain.model;

import java.util.List;

public record VersionDiff(
        String leftVersionId,
        String rightVersionId,
        List<DiffBlockEntry> changedBlocks,
        int changedChunks,
        List<StoredEntityChange> changedEntities
) {

    public VersionDiff(String leftVersionId, String rightVersionId, List<DiffBlockEntry> changedBlocks, int changedChunks) {
        this(leftVersionId, rightVersionId, changedBlocks, changedChunks, List.of());
    }

    public VersionDiff {
        changedBlocks = changedBlocks == null ? List.of() : List.copyOf(changedBlocks);
        changedEntities = changedEntities == null ? List.of() : List.copyOf(changedEntities);
    }

    public int changedBlockCount() {
        return this.changedBlocks.size();
    }

    public int changedEntityCount() {
        return this.changedEntities.size();
    }
}
