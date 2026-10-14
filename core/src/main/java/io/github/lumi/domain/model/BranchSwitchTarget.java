package io.github.lumi.domain.model;

import java.util.Objects;

/** Immutable branch identities needed to resume switch publication after a crash. */
public record BranchSwitchTarget(
        BranchName branch, long targetRevision, long expectedActiveRevision) {
    public BranchSwitchTarget {
        Objects.requireNonNull(branch, "branch");
        if (targetRevision < 0 || expectedActiveRevision < 0) {
            throw new IllegalArgumentException("Branch switch revisions cannot be negative");
        }
    }
}
