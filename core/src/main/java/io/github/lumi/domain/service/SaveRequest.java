package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.VersionTags;
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
        CommitKind kind,
        VersionTags tags,
        boolean replacesHead) {
    public SaveRequest {
        Objects.requireNonNull(expectedRef, "expectedRef");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(workspaceId, "workspaceId");
        zoneId = Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(tags, "tags");
        if (!tags.isEmpty() && kind != CommitKind.MANUAL
                && kind != CommitKind.AMEND && kind != CommitKind.ZONE) {
            throw new IllegalArgumentException("Only player Saves accept version tags");
        }
        if ((kind == CommitKind.AMEND && !replacesHead)
                || (replacesHead && kind != CommitKind.AMEND && kind != CommitKind.ZONE)) {
            throw new IllegalArgumentException("Invalid Save replacement mode");
        }
    }

    public SaveRequest(
            BranchRef expectedRef,
            CommitAuthor author,
            String message,
            Instant timestamp,
            UUID workspaceId,
            Optional<UUID> zoneId,
            CommitKind kind,
            VersionTags tags) {
        this(expectedRef, author, message, timestamp, workspaceId, zoneId,
                kind, tags, kind == CommitKind.AMEND);
    }

    public SaveRequest(
            BranchRef expectedRef,
            CommitAuthor author,
            String message,
            Instant timestamp,
            UUID workspaceId,
            Optional<UUID> zoneId,
            CommitKind kind) {
        this(expectedRef, author, message, timestamp, workspaceId, zoneId,
                kind, VersionTags.empty());
    }
}
