package io.github.lumi.domain.service;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.VersionTagRepository;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Owns mutable builder tags without weakening immutable commit identity. */
public final class VersionTagService {
    private final CommitRepository commits;
    private final VersionTagRepository tags;

    public VersionTagService(
            CommitRepository commits, VersionTagRepository tags) {
        this.commits = Objects.requireNonNull(commits, "commits");
        this.tags = Objects.requireNonNull(tags, "tags");
    }

    public VersionTags read(CommitId target) throws IOException {
        return tags.read(Objects.requireNonNull(target, "target"));
    }

    public void replace(
            CommitId target, UUID activeWorkspaceId, VersionTags replacement)
            throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(activeWorkspaceId, "activeWorkspaceId");
        Objects.requireNonNull(replacement, "replacement");
        var commit = commits.read(target);
        if (!commit.workspaceId().equals(activeWorkspaceId)) {
            throw new IOException("Version does not belong to the active workspace");
        }
        if (commit.kind() == CommitKind.HIDDEN_SAFETY
                || commit.kind() == CommitKind.HIDDEN_RETURN) {
            throw new IllegalArgumentException("Internal checkpoints cannot have tags");
        }
        tags.replace(target, replacement);
    }
}
