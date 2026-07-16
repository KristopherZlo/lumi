package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Immutable workspace and branch pointer expectations for one world switch. */
public record WorkspaceSwitchPlan(
        ActiveWorkspace expectedActive,
        UUID targetWorkspace,
        BranchSwitchPlan branch) {
    public WorkspaceSwitchPlan {
        Objects.requireNonNull(expectedActive, "expectedActive");
        Objects.requireNonNull(targetWorkspace, "targetWorkspace");
        Objects.requireNonNull(branch, "branch");
        if (expectedActive.id().equals(targetWorkspace)) {
            throw new IllegalArgumentException("Target workspace is already active");
        }
    }
}
