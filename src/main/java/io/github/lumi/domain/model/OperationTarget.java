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
        Optional<BlockAreaTarget> blockArea) {
    public OperationTarget {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(expectedHead, "expectedHead");
        target = Objects.requireNonNull(target, "target");
        returnPoint = Objects.requireNonNull(returnPoint, "returnPoint");
        branchSwitch = Objects.requireNonNull(branchSwitch, "branchSwitch");
        blockArea = Objects.requireNonNull(blockArea, "blockArea");
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
    }

    public OperationTarget(
            BranchName branch,
            CommitId expectedHead,
            long expectedRevision,
            Optional<CommitId> target,
            Optional<CommitId> returnPoint) {
        this(branch, expectedHead, expectedRevision, target, returnPoint,
                Optional.empty(), Optional.empty());
    }

    public OperationTarget(
            BranchName branch,
            CommitId expectedHead,
            long expectedRevision,
            Optional<CommitId> target,
            Optional<CommitId> returnPoint,
            Optional<BranchSwitchTarget> branchSwitch) {
        this(branch, expectedHead, expectedRevision, target, returnPoint,
                branchSwitch, Optional.empty());
    }
}
