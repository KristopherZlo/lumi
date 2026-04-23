package io.github.luma.domain.model;

import java.nio.file.Path;

public record HistoryPackageImportResult(
        Path archiveFile,
        String importedProjectName,
        String importedVariantId,
        String importedVariantName,
        ProjectArchiveManifest manifest
) {
}
