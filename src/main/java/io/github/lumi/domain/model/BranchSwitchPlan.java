package io.github.lumi.domain.model;

import java.util.Objects;

/** Immutable branch identities validated before a world switch starts. */
public record BranchSwitchPlan(ActiveBranch expectedActive, BranchRef target) {
    public BranchSwitchPlan {
        Objects.requireNonNull(expectedActive, "expectedActive");
        Objects.requireNonNull(target, "target");
        if (expectedActive.name().equals(target.name())) {
            throw new IllegalArgumentException("Target branch is already active");
        }
    }
}
