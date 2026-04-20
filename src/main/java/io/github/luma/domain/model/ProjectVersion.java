package io.github.luma.domain.model;

import java.time.Instant;
import java.util.List;

public record ProjectVersion(
        String id,
        String projectId,
        String variantId,
        String parentVersionId,
        String snapshotId,
        List<String> patchIds,
        String author,
        String message,
        ChangeStats stats,
        PreviewInfo preview,
        ExternalSourceInfo sourceInfo,
        Instant createdAt
) {
}
