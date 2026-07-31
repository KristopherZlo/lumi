package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.BranchSwitchPlan;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.BranchService;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Selects the target branch without moving either branch ref. */
public final class BranchSwitchRestorePublication implements RestorePublication {
    private final BranchService branches;
    private final BranchSwitchPlan plan;
    private final Optional<WorkingIndexClearPublication> working;

    public BranchSwitchRestorePublication(BranchService branches, BranchSwitchPlan plan) {
        this(branches, plan, Optional.empty());
    }

    public BranchSwitchRestorePublication(
            BranchService branches,
            BranchSwitchPlan plan,
            MutationDurabilityTracker mutations,
            WorkingIndexSnapshot captured) {
        this(branches, plan, Optional.of(new WorkingIndexClearPublication(
                mutations, captured)));
    }

    private BranchSwitchRestorePublication(
            BranchService branches,
            BranchSwitchPlan plan,
            Optional<WorkingIndexClearPublication> working) {
        this.branches = Objects.requireNonNull(branches, "branches");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.working = Objects.requireNonNull(working, "working");
    }

    @Override
    public void publish(PreparedRestore restore) throws IOException {
        if (!restore.expectedRef().equals(plan.source())
                || !restore.targetCommit().equals(plan.target().commit())) {
            throw new IOException("Prepared Restore does not match branch switch plan");
        }
        branches.validateSwitch(plan);
        working.ifPresent(clear -> clear.publish(restore));
        branches.completeSwitch(plan);
    }

    @Override
    public boolean isDurable() {
        return working.map(WorkingIndexClearPublication::isDurable).orElse(true);
    }

    @Override
    public boolean awaitDurable(long deadlineNanos) throws IOException {
        return working.isEmpty()
                || working.orElseThrow().awaitDurable(deadlineNanos);
    }
}
