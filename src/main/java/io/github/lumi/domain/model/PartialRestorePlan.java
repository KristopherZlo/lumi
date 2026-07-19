package io.github.lumi.domain.model;

import java.util.Objects;

/** Immutable exact summary of a read-only partial-Restore preparation. */
public record PartialRestorePlan(
        CommitId target,
        BlockAreaTarget area,
        int changedSections,
        long changedBlocks) {
    public PartialRestorePlan {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(area, "area");
        if (changedSections < 0 || changedBlocks < 0) {
            throw new IllegalArgumentException(
                    "Partial Restore plan counts cannot be negative");
        }
    }

    public boolean hasChanges() {
        return changedBlocks > 0;
    }
}
