package io.github.lumi.domain.model;

import java.util.Objects;

/** Immutable world endpoints retained by one Undo action. */
public record WorldActionCheckpoint(
        BranchName branch, CommitId before, CommitId after,
        BranchRef retainedRef, WorldActionScope scope) {
    public WorldActionCheckpoint {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(retainedRef, "retainedRef");
        Objects.requireNonNull(scope, "scope");
        if (!retainedRef.commit().equals(before) && !retainedRef.commit().equals(after)) {
            throw new IllegalArgumentException("Checkpoint ref must retain one of its endpoints");
        }
    }
}
