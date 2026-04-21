package io.github.luma.domain.model;

import java.util.Set;

public record ProjectCleanupPolicy(
        Set<String> referencedSnapshotFiles,
        Set<String> referencedPreviewFiles,
        boolean deleteOperationDraft
) {
}
