package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record SaveRequest(
        BranchRef expectedRef,
        CommitAuthor author,
        String message,
        Instant timestamp,
        UUID workspaceId,
        Optional<UUID> zoneId,
        CommitKind kind) {
    public SaveRequest {
        Objects.requireNonNull(expectedRef, "expectedRef");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(workspaceId, "workspaceId");
        zoneId = Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(kind, "kind");
    }
}
