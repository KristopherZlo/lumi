package io.github.lumi.domain.service;

import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitTombstone;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.TombstoneRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Soft-deletes builder history without deleting immutable commit data. */
public final class TombstoneService {
    private final CommitRepository commits;
    private final BranchRefRepository refs;
    private final TombstoneRepository tombstones;

    public TombstoneService(
            CommitRepository commits,
            BranchRefRepository refs,
            TombstoneRepository tombstones) {
        this.commits = Objects.requireNonNull(commits, "commits");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.tombstones = Objects.requireNonNull(tombstones, "tombstones");
    }

    public synchronized CommitTombstone softDelete(
            CommitId target,
            UUID workspaceId,
            CommitAuthor deletedBy,
            Instant deletedAt) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(deletedBy, "deletedBy");
        Objects.requireNonNull(deletedAt, "deletedAt");
        var commit = commits.read(target);
        if (!commit.workspaceId().equals(workspaceId)) {
            throw new IOException("Commit does not belong to the active workspace");
        }
        if (commit.kind() == CommitKind.HIDDEN_RETURN
                || commit.kind() == CommitKind.HIDDEN_SAFETY) {
            throw new IllegalArgumentException("Internal checkpoints cannot be deleted");
        }
        var pointing = refs.list().stream()
                .filter(ref -> ref.commit().equals(target))
                .toList();
        CommitId parent = null;
        if (!pointing.isEmpty()) {
            if (commit.parents().isEmpty()) {
                throw new IllegalStateException(
                        "Cannot delete the root version of an active branch");
            }
            parent = commit.parents().getFirst();
            if (!commits.read(parent).workspaceId().equals(workspaceId)) {
                throw new IllegalStateException(
                        "Cannot move a branch outside its workspace");
            }
        }
        for (var ref : pointing) {
            if (ref.name().value().startsWith("hidden/auto/")) {
                refs.delete(ref);
            } else {
                refs.compareAndSet(ref, parent);
            }
        }
        CommitTombstone tombstone = tombstones.read(target).orElseGet(() ->
                new CommitTombstone(target, deletedBy, deletedAt));
        return tombstones.create(tombstone);
    }

    public synchronized void cleanup(CommitId target) throws IOException {
        tombstones.delete(Objects.requireNonNull(target, "target"));
    }
}
