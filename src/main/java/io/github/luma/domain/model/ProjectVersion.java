package io.github.luma.domain.model;

import java.time.Instant;
import java.util.List;

public record ProjectVersion(
        String id,
        String projectId,
        String variantId,
        String parentVersionId,
        String snapshotId,
        String entityCheckpointId,
        List<String> patchIds,
        VersionKind versionKind,
        String author,
        String message,
        ChangeStats stats,
        PreviewInfo preview,
        ExternalSourceInfo sourceInfo,
        Instant createdAt
) {

    public ProjectVersion(
            String id,
            String projectId,
            String variantId,
            String parentVersionId,
            String snapshotId,
            List<String> patchIds,
            VersionKind versionKind,
            String author,
            String message,
            ChangeStats stats,
            PreviewInfo preview,
            ExternalSourceInfo sourceInfo,
            Instant createdAt
    ) {
        this(
                id,
                projectId,
                variantId,
                parentVersionId,
                snapshotId,
                "",
                patchIds,
                versionKind,
                author,
                message,
                stats,
                preview,
                sourceInfo,
                createdAt
        );
    }
}
