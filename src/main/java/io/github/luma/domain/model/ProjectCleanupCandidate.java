package io.github.luma.domain.model;

public record ProjectCleanupCandidate(
        String relativePath,
        String reason,
        long sizeBytes
) {
}
