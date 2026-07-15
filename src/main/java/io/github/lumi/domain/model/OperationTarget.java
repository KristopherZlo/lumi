package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.Optional;

public record OperationTarget(
        BranchName branch,
        CommitId expectedHead,
        long expectedRevision,
        Optional<CommitId> target,
        Optional<CommitId> returnPoint) {
    public OperationTarget {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(expectedHead, "expectedHead");
        target = Objects.requireNonNull(target, "target");
        returnPoint = Objects.requireNonNull(returnPoint, "returnPoint");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected ref revision cannot be negative");
        }
    }
}
