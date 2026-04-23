package io.github.luma.domain.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        List<BlockPoint> conflictPositions
) {

    public boolean hasConflicts() {
        return !this.conflictPositions.isEmpty();
    }

    public int mergeBlockCount() {
        return this.mergeChanges.size();
    }

    public int conflictChunkCount() {
        Set<Long> chunks = new LinkedHashSet<>();
        for (BlockPoint pos : this.conflictPositions) {
            chunks.add((((long) pos.x()) >> 4 << 32) ^ ((((long) pos.z()) >> 4) & 0xffffffffL));
        }
        return chunks.size();
    }

    public List<BlockPoint> sampleConflictPositions(int limit) {
        return this.conflictPositions.stream().limit(Math.max(0, limit)).toList();
    }
}
