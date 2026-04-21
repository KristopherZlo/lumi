package io.github.luma.domain.model;

import java.util.Set;

public record ProjectCleanupPolicy(
        Set<String> referencedSnapshotFiles,
        Set<String> referencedPreviewFiles,
        boolean deleteOperationDraft
) {

    public ProjectCleanupPolicy {
        referencedSnapshotFiles = referencedSnapshotFiles == null
                ? Set.of()
                : Set.copyOf(referencedSnapshotFiles);
        referencedPreviewFiles = referencedPreviewFiles == null
                ? Set.of()
                : Set.copyOf(referencedPreviewFiles);
    }
}
