package io.github.luma.domain.model;

import java.nio.file.Path;

public record ProjectArchiveImportResult(
        Path archiveFile,
        ProjectArchiveManifest manifest
) {
}
