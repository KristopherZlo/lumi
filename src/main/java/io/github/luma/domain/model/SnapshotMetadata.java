package io.github.luma.domain.model;

import java.util.List;

public record SnapshotMetadata(
        String id,
        String projectId,
        String dataFileName,
        List<ChunkPayloadSlice> chunks,
        int sectionCount,
        int entityCount,
        long sizeBytes
) {

    public SnapshotMetadata {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}
