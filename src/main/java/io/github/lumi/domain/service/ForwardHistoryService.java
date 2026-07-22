package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Retains and locates branch continuations made unreachable by Restore. */
public final class ForwardHistoryService {
    private static final String PREFIX = "hidden/forward/";
    private static final String LEGACY_RETURN_PREFIX = "hidden/return/";
    private static final int MAX_ROOTS = 1_000;
    private final CommitRepository commits;
    private final BranchRefRepository refs;

    public ForwardHistoryService(CommitRepository commits, BranchRefRepository refs) {
        this.commits = Objects.requireNonNull(commits, "commits");
        this.refs = Objects.requireNonNull(refs, "refs");
    }

    public void retain(BranchRef checkpointRef) throws IOException {
        Objects.requireNonNull(checkpointRef, "checkpointRef");
        var checkpoint = commits.read(checkpointRef.commit());
        if (checkpoint.kind() == CommitKind.HIDDEN_RETURN && checkpoint.parents().isEmpty()) {
            throw new IOException("Restore checkpoint has no source history");
        }
        CommitId source = checkpoint.kind() == CommitKind.HIDDEN_RETURN
                ? checkpoint.parents().getFirst() : checkpointRef.commit();
        BranchName name = new BranchName(prefix(checkpointRef.name()) + source.hex());
        Optional<BranchRef> existing = refs.read(name);
        if (existing.isPresent()) {
            if (!existing.orElseThrow().commit().equals(source)) {
                throw new IOException("Forward-history ref points to a different commit: " + name);
            }
            return;
        }
        refs.create(name, source);
    }

    public List<CommitId> roots(BranchName branch, Optional<UUID> workspaceId)
            throws IOException {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(workspaceId, "workspaceId");
        List<BranchRef> all = refs.list();
        LinkedHashSet<CommitId> roots = new LinkedHashSet<>();
        List<BranchRef> legacyReturns = new ArrayList<>();
        String prefix = prefix(branch);
        for (BranchRef ref : all) {
            if (ref.name().value().startsWith(prefix)) {
                roots.add(ref.commit());
            } else if (ref.name().value().startsWith(LEGACY_RETURN_PREFIX)) {
                legacyReturns.add(ref);
            }
        }
        if (!legacyReturns.isEmpty() && isOnlyVisibleBranch(all, branch, workspaceId)) {
            for (BranchRef ref : legacyReturns) {
                var checkpoint = commits.read(ref.commit());
                if (checkpoint.kind() == CommitKind.HIDDEN_RETURN
                        && !checkpoint.parents().isEmpty()
                        && workspaceId.map(checkpoint.workspaceId()::equals).orElse(true)) {
                    roots.add(checkpoint.parents().getFirst());
                }
            }
        }
        return roots.stream().limit(MAX_ROOTS).toList();
    }

    private boolean isOnlyVisibleBranch(
            List<BranchRef> all, BranchName branch, Optional<UUID> workspaceId)
            throws IOException {
        int matching = 0;
        for (BranchRef ref : all) {
            if (ref.name().value().startsWith("hidden/")) {
                continue;
            }
            if (workspaceId.isPresent()
                    && !commits.read(ref.commit()).workspaceId().equals(workspaceId.orElseThrow())) {
                continue;
            }
            if (!ref.name().equals(branch) || ++matching > 1) {
                return false;
            }
        }
        return matching == 1;
    }

    private static String prefix(BranchName branch) {
        String branchId = ObjectId.hash(
                branch.value().getBytes(StandardCharsets.UTF_8)).hex();
        return PREFIX + branchId + "/";
    }
}
