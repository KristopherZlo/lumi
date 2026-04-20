package io.github.luma.domain.model;

import java.util.List;

public record BlockPatch(
        String id,
        String projectId,
        String versionId,
        List<ChunkDelta> chunkDeltas,
        PatchStats stats
) {
}
