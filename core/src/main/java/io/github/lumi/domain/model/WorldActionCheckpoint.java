package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.Optional;

/** Immutable world endpoints retained by one Undo action. */
public record WorldActionCheckpoint(
        BranchName branch, CommitId before, CommitId after,
        BranchRef retainedRef, WorldActionScope scope, Optional<WorldActionContext.Change> context) {
    public WorldActionCheckpoint(BranchName branch, CommitId before, CommitId after, BranchRef retainedRef, WorldActionScope scope) {
        this(branch, before, after, retainedRef, scope, Optional.empty());
    }

    public WorldActionCheckpoint withContext(WorldActionContext.Change change) {
        return new WorldActionCheckpoint(branch, before, after, retainedRef, scope, Optional.of(change));
    }
    public WorldActionCheckpoint {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(retainedRef, "retainedRef");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(context, "context");
        if (context.isPresent() && !context.orElseThrow().before().branch().equals(branch)) {
            throw new IllegalArgumentException("Checkpoint source branch does not match its context");
        }
        if (!retainedRef.commit().equals(before) && !retainedRef.commit().equals(after)) {
            throw new IllegalArgumentException("Checkpoint ref must retain one of its endpoints");
        }
    }
}
