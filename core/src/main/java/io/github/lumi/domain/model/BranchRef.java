package io.github.lumi.domain.model;

import java.util.Objects;

public record BranchRef(BranchName name, CommitId commit, long revision) {
    public BranchRef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(commit, "commit");
        if (revision < 0) {
            throw new IllegalArgumentException("Ref revision cannot be negative");
        }
    }
}
