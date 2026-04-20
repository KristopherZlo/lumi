package io.github.luma.domain.model;

import java.util.List;

public record PatchMetadata(
        String id,
        String projectId,
        String versionId,
        String dataFileName,
        List<PatchChunkSlice> chunks,
        PatchStats stats
) {
}
