package io.github.luma.domain.model;

import java.util.List;

public record VersionDiff(
        String leftVersionId,
        String rightVersionId,
        List<DiffBlockEntry> changedBlocks,
        int changedChunks
) {

    public int changedBlockCount() {
        return this.changedBlocks.size();
    }
}
