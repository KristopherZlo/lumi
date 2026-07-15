package io.github.lumi.domain.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record Commit(
        ObjectId tree,
        List<CommitId> parents,
        CommitAuthor author,
        String message,
        Instant timestamp,
        UUID workspaceId,
        Optional<UUID> zoneId,
        CommitKind kind,
        CommitStatistics statistics) {
    public Commit {
        Objects.requireNonNull(tree, "tree");
        parents = List.copyOf(Objects.requireNonNull(parents, "parents"));
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(workspaceId, "workspaceId");
        zoneId = Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(statistics, "statistics");
        if (parents.size() > 2 || new HashSet<>(parents).size() != parents.size()) {
            throw new IllegalArgumentException("Commit must have zero, one or two distinct parents");
        }
    }
}
