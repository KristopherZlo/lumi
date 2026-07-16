package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Workspace pointer identities needed to finish an interrupted switch. */
public record WorkspaceSwitchTarget(
        UUID expectedWorkspace, UUID targetWorkspace, long expectedRevision) {
    public WorkspaceSwitchTarget {
        Objects.requireNonNull(expectedWorkspace, "expectedWorkspace");
        Objects.requireNonNull(targetWorkspace, "targetWorkspace");
        if (expectedWorkspace.equals(targetWorkspace)) {
            throw new IllegalArgumentException("Workspace switch target must be different");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Workspace switch revision cannot be negative");
        }
    }
}
