package io.github.lumi.domain.model;

import java.util.Objects;

/** Revisioned pointer selecting the branch used by dimension operations. */
public record ActiveBranch(BranchName name, long revision) {
    public ActiveBranch {
        Objects.requireNonNull(name, "name");
        if (revision < 0) {
            throw new IllegalArgumentException("Active branch revision cannot be negative");
        }
    }
}
