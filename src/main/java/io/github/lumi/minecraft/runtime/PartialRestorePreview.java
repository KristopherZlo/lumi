package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.PartialRestorePlan;
import java.util.Objects;
import java.util.UUID;

/** One clean-state partial-Restore plan accepted for a later guarded apply. */
public record PartialRestorePreview(
        UUID token,
        UUID workspaceId,
        BranchRef expectedRef,
        PartialRestorePlan plan) {
    public PartialRestorePreview {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(expectedRef, "expectedRef");
        Objects.requireNonNull(plan, "plan");
    }
}
