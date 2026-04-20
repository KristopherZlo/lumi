package io.github.luma.domain.model;

import java.time.Instant;
import java.util.List;

public record SnapshotData(
        String projectId,
        Instant createdAt,
        int minBuildHeight,
        int maxBuildHeight,
        List<SnapshotChunkData> chunks
) {
}
