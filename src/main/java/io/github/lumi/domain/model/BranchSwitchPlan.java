package io.github.lumi.domain.model;

import java.util.Objects;

/** Immutable branch identities validated before a world switch starts. */
public record BranchSwitchPlan(
        ActiveBranch expectedActive, BranchRef source, BranchRef target) {
    public BranchSwitchPlan {
        Objects.requireNonNull(expectedActive, "expectedActive");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (!expectedActive.name().equals(source.name())) {
            throw new IllegalArgumentException("Source ref is not the active branch");
        }
        if (expectedActive.name().equals(target.name())) {
            throw new IllegalArgumentException("Target branch is already active");
        }
    }
}
