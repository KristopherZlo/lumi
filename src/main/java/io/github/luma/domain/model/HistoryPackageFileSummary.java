package io.github.luma.domain.model;

import java.nio.file.Path;
import java.time.Instant;

public record HistoryPackageFileSummary(
        Path archiveFile,
        String fileName,
        long sizeBytes,
        Instant updatedAt
) {
    public HistoryPackageFileSummary {
        if (archiveFile == null) {
            throw new IllegalArgumentException("archiveFile is required");
        }
        fileName = fileName == null || fileName.isBlank()
                ? archiveFile.getFileName().toString()
                : fileName;
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }
}
