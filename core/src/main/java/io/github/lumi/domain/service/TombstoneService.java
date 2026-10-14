package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitTombstone;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.TombstoneRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Soft-deletes builder history without deleting immutable commit data. */
public final class TombstoneService {
    private static final String RECOVERY_REF_PREFIX = "hidden/deleted/";
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
            preserveDeletedHead(target, ref);
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

    public synchronized void restore(CommitId target, UUID workspaceId)
            throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(workspaceId, "workspaceId");
        var commit = commits.read(target);
        if (!commit.workspaceId().equals(workspaceId)) {
            throw new IOException("Commit does not belong to the active workspace");
        }
        if (tombstones.read(target).isEmpty()) {
            throw new IllegalStateException("Version is not soft-deleted");
        }
        boolean restoredHead = false;
        for (BranchRef recovery : recoveryRefs(target)) {
            BranchName original = originalBranch(recovery.name(), target);
            var current = refs.read(original);
            if (!original.value().startsWith("hidden/")
                    && current.isPresent() && !commit.parents().isEmpty()
                    && current.orElseThrow().commit().equals(commit.parents().getFirst())) {
                refs.compareAndSet(current.orElseThrow(), target);
                restoredHead = true;
            }
            refs.delete(recovery);
        }
        if (!restoredHead && !isReachable(target)) {
            createRestoredBranch(target, workspaceId);
        }
        tombstones.delete(target);
    }

    public synchronized List<io.github.lumi.domain.model.HistoryEntry> deleted(
            UUID workspaceId, int limit) throws IOException {
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException(
                    "Deleted-version limit must be between 1 and 1000");
        }
        var markers = new ArrayList<>(tombstones.list());
        markers.sort(java.util.Comparator.comparing(
                io.github.lumi.domain.model.CommitTombstone::deletedAt).reversed());
        var result = new ArrayList<io.github.lumi.domain.model.HistoryEntry>();
        for (var marker : markers) {
            var commit = commits.read(marker.commit());
            if (commit.workspaceId().equals(workspaceId)) {
                result.add(new io.github.lumi.domain.model.HistoryEntry(
                        marker.commit(), commit));
                if (result.size() == limit) {
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    public synchronized void cleanup(CommitId target, UUID workspaceId)
            throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(workspaceId, "workspaceId");
        var commit = commits.read(target);
        if (!commit.workspaceId().equals(workspaceId)) {
            throw new IOException("Commit does not belong to the active workspace");
        }
        if (tombstones.read(target).isEmpty()) {
            throw new IllegalStateException("Version is not soft-deleted");
        }
        for (BranchRef recovery : recoveryRefs(target)) {
            refs.delete(recovery);
        }
        if (isReachable(target)) {
            throw new IllegalStateException(
                    "Deleted version is still required by retained history");
        }
        tombstones.delete(target);
    }

    private void preserveDeletedHead(CommitId target, BranchRef original)
            throws IOException {
        BranchName recovery = recoveryRef(target, original.name());
        var existing = refs.read(recovery);
        if (existing.isPresent()) {
            if (!existing.orElseThrow().commit().equals(target)) {
                throw new IllegalStateException("Deleted-version recovery ref conflicts");
            }
            return;
        }
        refs.create(recovery, target);
    }

    private List<BranchRef> recoveryRefs(CommitId target) throws IOException {
        String prefix = RECOVERY_REF_PREFIX + target.hex() + "/";
        return refs.list().stream()
                .filter(ref -> ref.name().value().startsWith(prefix))
                .toList();
    }

    private static BranchName recoveryRef(CommitId target, BranchName original) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                original.value().getBytes(StandardCharsets.UTF_8));
        return new BranchName(RECOVERY_REF_PREFIX + target.hex() + "/" + encoded);
    }

    private static BranchName originalBranch(BranchName recovery, CommitId target)
            throws IOException {
        String prefix = RECOVERY_REF_PREFIX + target.hex() + "/";
        try {
            return new BranchName(new String(
                    Base64.getUrlDecoder().decode(
                            recovery.value().substring(prefix.length())),
                    StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid deleted-version recovery ref", invalid);
        }
    }

    private void createRestoredBranch(CommitId target, UUID workspaceId)
            throws IOException {
        String base = "restored-" + target.hex().substring(0, 8);
        for (int attempt = 1; attempt <= 1_000; attempt++) {
            String suffix = attempt == 1 ? "" : "-" + attempt;
            BranchName name = WorkspaceService.branchName(
                    workspaceId, new BranchName(base + suffix));
            var existing = refs.read(name);
            if (existing.isEmpty()) {
                refs.create(name, target);
                return;
            }
            if (existing.orElseThrow().commit().equals(target)) {
                return;
            }
        }
        throw new IOException("Cannot allocate a restored branch name");
    }

    private boolean isReachable(CommitId target) throws IOException {
        var pending = new ArrayDeque<CommitId>();
        refs.list().forEach(ref -> pending.add(ref.commit()));
        var visited = new HashSet<CommitId>();
        while (!pending.isEmpty()) {
            CommitId current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(target)) {
                return true;
            }
            pending.addAll(commits.read(current).parents());
        }
        return false;
    }
}
