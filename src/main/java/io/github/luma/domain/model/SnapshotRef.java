package io.github.luma.domain.model;

import java.time.Instant;

public record SnapshotRef(
        String id,
        String projectId,
        String fileName,
        Bounds3i bounds,
        long sizeBytes,
        Instant createdAt
) {
}
