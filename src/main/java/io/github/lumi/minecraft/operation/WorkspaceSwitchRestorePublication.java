package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.WorkspaceSwitchPlan;
import io.github.lumi.domain.service.BranchService;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.domain.service.WorkspaceService;
import java.io.IOException;
import java.util.Objects;

/** Crash-idempotently selects the restored workspace and its main branch. */
public final class WorkspaceSwitchRestorePublication implements RestorePublication {
    private final BranchService branches;
    private final WorkspaceService workspaces;
    private final WorkspaceSwitchPlan plan;

    public WorkspaceSwitchRestorePublication(
            BranchService branches,
            WorkspaceService workspaces,
            WorkspaceSwitchPlan plan) {
        this.branches = Objects.requireNonNull(branches, "branches");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.plan = Objects.requireNonNull(plan, "plan");
    }

    @Override
    public void publish(PreparedRestore restore) throws IOException {
        if (!restore.expectedRef().equals(plan.branch().source())
                || !restore.targetCommit().equals(plan.branch().target().commit())) {
            throw new IOException("Prepared Restore does not match workspace switch plan");
        }
        branches.completeSwitchIdempotent(plan.branch());
        workspaces.completeSwitchIdempotent(plan);
    }
}
