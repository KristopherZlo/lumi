package io.github.lumi.domain.service;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.VersionDisplayName;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.VersionDisplayNameRepository;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Owns builder-facing names without weakening immutable commit identity. */
public final class VersionDisplayNameService {
    private final CommitRepository commits;
    private final VersionDisplayNameRepository names;

    public VersionDisplayNameService(
            CommitRepository commits, VersionDisplayNameRepository names) {
        this.commits = Objects.requireNonNull(commits, "commits");
        this.names = Objects.requireNonNull(names, "names");
    }

    public String read(CommitId target, String commitMessage) throws IOException {
        Objects.requireNonNull(commitMessage, "commitMessage");
        return names.read(Objects.requireNonNull(target, "target"))
                .map(VersionDisplayName::value).orElse(commitMessage);
    }

    public void replace(
            CommitId target, UUID activeWorkspaceId, VersionDisplayName replacement)
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
            throw new IllegalArgumentException("Internal checkpoints cannot be renamed");
        }
        names.replace(target, replacement);
    }
}
