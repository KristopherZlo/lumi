package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.UUID;

/** The dimension's shared selection; saved branch refs are independent of this pointer. */
public record WorldActionContext(UUID workspace, BranchName branch) {
    public WorldActionContext {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(branch, "branch");
    }

    public record Change(WorldActionContext before, WorldActionContext after) {
        public Change {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
            if (before.equals(after)) throw new IllegalArgumentException("Context did not change");
        }

        public WorldActionContext endpoint(boolean applied) { return applied ? after : before; }
    }
}
