package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.BranchSwitchPlan;
import io.github.lumi.domain.service.BranchService;
import io.github.lumi.domain.service.PreparedRestore;
import java.io.IOException;
import java.util.Objects;

/** Selects the target branch without moving either branch ref. */
public final class BranchSwitchRestorePublication implements RestorePublication {
    private final BranchService branches;
    private final BranchSwitchPlan plan;

    public BranchSwitchRestorePublication(BranchService branches, BranchSwitchPlan plan) {
        this.branches = Objects.requireNonNull(branches, "branches");
        this.plan = Objects.requireNonNull(plan, "plan");
    }

    @Override
    public void publish(PreparedRestore restore) throws IOException {
        if (!restore.expectedRef().equals(plan.source())
                || !restore.targetCommit().equals(plan.target().commit())) {
            throw new IOException("Prepared Restore does not match branch switch plan");
        }
        branches.completeSwitch(plan);
    }
}
