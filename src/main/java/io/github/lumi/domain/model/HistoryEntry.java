package io.github.lumi.domain.model;

import java.util.Objects;

/** Immutable commit identity plus decoded metadata for history views. */
public record HistoryEntry(CommitId id, Commit commit) {
    public HistoryEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(commit, "commit");
    }
}
