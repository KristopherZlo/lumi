package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.HistoryEntry;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Owns bounded ref-neutral automatic versions for one source branch. */
public final class AutoVersionService {
    private static final int MAX_QUERY = 1_000;
    private static final String PREFIX = "hidden/auto/";
    private final CommitRepository commits;
    private final BranchRefRepository refs;

    public AutoVersionService(CommitRepository commits, BranchRefRepository refs) {
        this.commits = Objects.requireNonNull(commits, "commits");
        this.refs = Objects.requireNonNull(refs, "refs");
    }

    public BranchName refName(BranchName source, UUID id) {
        Objects.requireNonNull(id, "id");
        return new BranchName(prefix(source) + id);
    }

    public int prune(BranchName source, int keep) throws IOException {
        if (keep < 0) {
            throw new IllegalArgumentException("Retention count cannot be negative");
        }
        List<VersionRef> versions = versions(source);
        for (int index = keep; index < versions.size(); index++) {
            refs.delete(versions.get(index).ref());
        }
        return Math.max(0, versions.size() - keep);
    }

    public List<HistoryEntry> list(BranchName source, UUID workspaceId, int limit)
            throws IOException {
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (limit < 1 || limit > MAX_QUERY) {
            throw new IllegalArgumentException(
                    "Auto-version limit must be between 1 and " + MAX_QUERY);
        }
        return versions(source).stream()
                .map(VersionRef::entry)
                .filter(entry -> entry.commit().workspaceId().equals(workspaceId))
                .limit(limit)
                .toList();
    }

    private List<VersionRef> versions(BranchName source) throws IOException {
        String prefix = prefix(source);
        var found = new ArrayList<VersionRef>();
        for (BranchRef ref : refs.list()) {
            if (!ref.name().value().startsWith(prefix)) {
                continue;
            }
            var commit = commits.read(ref.commit());
            if (commit.kind() == CommitKind.AUTO) {
                found.add(new VersionRef(ref, new HistoryEntry(ref.commit(), commit)));
            }
        }
        found.sort(Comparator
                .comparing((VersionRef version) -> version.entry().commit().timestamp())
                .reversed()
                .thenComparing(version -> version.ref().name().value()));
        return List.copyOf(found);
    }

    private static String prefix(BranchName source) {
        Objects.requireNonNull(source, "source");
        String branchId = ObjectId.hash(
                source.value().getBytes(StandardCharsets.UTF_8)).hex();
        return PREFIX + branchId + "/";
    }

    private record VersionRef(BranchRef ref, HistoryEntry entry) { }
}
