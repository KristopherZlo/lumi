package io.github.luma.domain.model;

import java.util.Set;

public record ProjectCleanupPolicy(
        Set<String> referencedSnapshotFiles,
        Set<String> referencedEntityCheckpointFiles,
        Set<String> referencedPreviewFiles,
        boolean deleteOperationDraft
) {

    public ProjectCleanupPolicy(
            Set<String> referencedSnapshotFiles,
            Set<String> referencedPreviewFiles,
            boolean deleteOperationDraft
    ) {
        this(referencedSnapshotFiles, Set.of(), referencedPreviewFiles, deleteOperationDraft);
    }

    public ProjectCleanupPolicy {
        referencedSnapshotFiles = referencedSnapshotFiles == null
                ? Set.of()
                : Set.copyOf(referencedSnapshotFiles);
        referencedEntityCheckpointFiles = referencedEntityCheckpointFiles == null
                ? Set.of()
                : Set.copyOf(referencedEntityCheckpointFiles);
        referencedPreviewFiles = referencedPreviewFiles == null
                ? Set.of()
                : Set.copyOf(referencedPreviewFiles);
    }
}
