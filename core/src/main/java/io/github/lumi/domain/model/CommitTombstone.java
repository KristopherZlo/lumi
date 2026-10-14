package io.github.lumi.domain.model;

import java.time.Instant;
import java.util.Objects;

/** Durable soft-delete marker that keeps its immutable commit reachable. */
public record CommitTombstone(
        CommitId commit,
        CommitAuthor deletedBy,
        Instant deletedAt) {
    public CommitTombstone {
        Objects.requireNonNull(commit, "commit");
        Objects.requireNonNull(deletedBy, "deletedBy");
        Objects.requireNonNull(deletedAt, "deletedAt");
    }
}
