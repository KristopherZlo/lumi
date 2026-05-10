package io.github.luma.domain.model;

import java.util.List;

public record PatchMetadata(
        String id,
        String projectId,
        String versionId,
        String dataFileName,
        List<PatchChunkSlice> chunks,
        PatchStats stats,
        List<PatchEntityChunkIndex> entityChunkIndex
) {

    public PatchMetadata {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        entityChunkIndex = entityChunkIndex == null ? List.of() : List.copyOf(entityChunkIndex);
    }

    public PatchMetadata(
            String id,
            String projectId,
            String versionId,
            String dataFileName,
            List<PatchChunkSlice> chunks,
            PatchStats stats
    ) {
        this(id, projectId, versionId, dataFileName, chunks, stats, List.of());
    }
}
