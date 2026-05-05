package io.github.luma.domain.model;

import java.time.Instant;

public record RestoreReturnPoint(
        String projectId,
        String variantId,
        String versionId,
        Instant createdAt,
        String restoreTargetVersionId
) {

    public RestoreReturnPoint {
        projectId = projectId == null ? "" : projectId;
        variantId = variantId == null ? "" : variantId;
        versionId = versionId == null ? "" : versionId;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        restoreTargetVersionId = restoreTargetVersionId == null ? "" : restoreTargetVersionId;
    }

    public boolean valid() {
        return !this.projectId.isBlank()
                && !this.variantId.isBlank()
                && !this.versionId.isBlank();
    }
}
