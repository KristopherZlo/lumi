package io.github.luma.domain.model;

import java.time.Instant;
import java.util.List;

public record ProjectArchiveManifest(
        int schemaVersion,
        ProjectArchiveScope scope,
        String projectName,
        String projectFolderName,
        String projectId,
        Instant exportedAt,
        boolean includesPreviews,
        List<ProjectArchiveEntry> entries
) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public ProjectArchiveScope scopeOrDefault() {
        return this.scope == null ? ProjectArchiveScope.project() : this.scope;
    }
}
