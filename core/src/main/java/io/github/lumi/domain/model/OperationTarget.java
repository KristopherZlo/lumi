package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.Optional;

public record OperationTarget(
        BranchName branch,
        CommitId expectedHead,
        long expectedRevision,
        Optional<CommitId> target,
        Optional<CommitId> returnPoint,
        Optional<BranchSwitchTarget> branchSwitch,
        Optional<BlockAreaTarget> blockArea,
        boolean excludeEntities,
        Optional<WorkspaceSwitchTarget> workspaceSwitch,
        Optional<ZoneRestoreTarget> zoneRestore) {
    public OperationTarget {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(expectedHead, "expectedHead");
        target = Objects.requireNonNull(target, "target");
        returnPoint = Objects.requireNonNull(returnPoint, "returnPoint");
        branchSwitch = Objects.requireNonNull(branchSwitch, "branchSwitch");
        blockArea = Objects.requireNonNull(blockArea, "blockArea");
        workspaceSwitch = Objects.requireNonNull(workspaceSwitch, "workspaceSwitch");
        zoneRestore = Objects.requireNonNull(zoneRestore, "zoneRestore");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected ref revision cannot be negative");
        }
        if (branchSwitch.isPresent()) {
            if (target.isEmpty()) {
                throw new IllegalArgumentException("Branch switch requires a target commit");
            }
            if (branch.equals(branchSwitch.orElseThrow().branch())) {
                throw new IllegalArgumentException("Branch switch target must be different");
            }
        }
        if (branchSwitch.isPresent() && blockArea.isPresent()) {
            throw new IllegalArgumentException("Branch switch cannot be a partial Restore");
        }
        if (workspaceSwitch.isPresent() && branchSwitch.isEmpty()) {
            throw new IllegalArgumentException("Workspace switch requires a branch switch");
        }
        if (zoneRestore.isPresent()
                && (target.isEmpty() || branchSwitch.isPresent() || blockArea.isPresent())) {
            throw new IllegalArgumentException("Zone Restore requires one ref-neutral target");
        }
    }

    public OperationTarget(
            BranchName branch,
            CommitId expectedHead,
            long expectedRevision,
            Optional<CommitId> target,
            Optional<CommitId> returnPoint,
            Optional<BranchSwitchTarget> branchSwitch,
            Optional<BlockAreaTarget> blockArea,
            boolean excludeEntities,
            Optional<WorkspaceSwitchTarget> workspaceSwitch) {
        this(branch, expectedHead, expectedRevision, target, returnPoint,
                branchSwitch, blockArea, excludeEntities, workspaceSwitch, Optional.empty());
    }

    public OperationTarget(
            BranchName branch,
            CommitId expectedHead,
            long expectedRevision,
            Optional<CommitId> target,
            Optional<CommitId> returnPoint,
            Optional<BranchSwitchTarget> branchSwitch,
            Optional<BlockAreaTarget> blockArea,
            boolean excludeEntities) {
        this(branch, expectedHead, expectedRevision, target, returnPoint,
                branchSwitch, blockArea, excludeEntities, Optional.empty(), Optional.empty());
    }

    public OperationTarget(
            BranchName branch,
            CommitId expectedHead,
            long expectedRevision,
            Optional<CommitId> target,
            Optional<CommitId> returnPoint) {
        this(branch, expectedHead, expectedRevision, target, returnPoint,
                Optional.empty(), Optional.empty(), false, Optional.empty(), Optional.empty());
    }

    public OperationTarget(
            BranchName branch,
            CommitId expectedHead,
            long expectedRevision,
            Optional<CommitId> target,
            Optional<CommitId> returnPoint,
            Optional<BranchSwitchTarget> branchSwitch) {
        this(branch, expectedHead, expectedRevision, target, returnPoint,
                branchSwitch, Optional.empty(), false, Optional.empty(), Optional.empty());
    }

    public OperationTarget(
            BranchName branch,
            CommitId expectedHead,
            long expectedRevision,
            Optional<CommitId> target,
            Optional<CommitId> returnPoint,
            Optional<BranchSwitchTarget> branchSwitch,
            Optional<BlockAreaTarget> blockArea) {
        this(branch, expectedHead, expectedRevision, target, returnPoint,
                branchSwitch, blockArea, false, Optional.empty(), Optional.empty());
    }
}
