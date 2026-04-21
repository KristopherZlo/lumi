package io.github.luma.domain.model;

import java.time.Instant;
import java.util.List;

public record ProjectArchiveManifest(
        int schemaVersion,
        String projectName,
        String projectFolderName,
        String projectId,
        Instant exportedAt,
        boolean includesPreviews,
        List<ProjectArchiveEntry> entries
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
