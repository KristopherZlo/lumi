package io.github.luma.domain.model;

import java.nio.file.Path;

public record ProjectArchiveExportResult(
        Path archiveFile,
        ProjectArchiveManifest manifest
) {
}
