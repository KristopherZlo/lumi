package io.github.luma.domain.model;

public record ProjectCleanupCandidate(
        String relativePath,
        String reason,
        long sizeBytes
) {

    public ProjectCleanupCandidate {
        relativePath = relativePath == null ? "" : relativePath;
        reason = reason == null ? "" : reason;
        sizeBytes = Math.max(0L, sizeBytes);
    }
}
