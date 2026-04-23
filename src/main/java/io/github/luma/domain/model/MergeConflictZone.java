package io.github.luma.domain.model;

import java.util.List;

public record MergeConflictZone(
        String id,
        List<ChunkPoint> chunks,
        Bounds3i bounds,
        List<StoredBlockChange> importedChanges
) {

    public int chunkCount() {
        return this.chunks.size();
    }

    public int blockCount() {
        return this.importedChanges.size();
    }

    public List<BlockPoint> positions() {
        return this.importedChanges.stream().map(StoredBlockChange::pos).toList();
    }

    public List<BlockPoint> samplePositions(int limit) {
        return this.positions().stream().limit(Math.max(0, limit)).toList();
    }
}
