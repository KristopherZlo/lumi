package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.HistoryEntry;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Produces bounded immutable history views without decoding world payloads. */
public final class HistoryQueryService {
    private static final int MAX_QUERY = 1_000;
    private final CommitRepository commits;
    private final BranchRefRepository refs;

    public HistoryQueryService(CommitRepository commits, BranchRefRepository refs) {
        this.commits = Objects.requireNonNull(commits, "commits");
        this.refs = Objects.requireNonNull(refs, "refs");
    }

    public List<HistoryEntry> firstParent(BranchName branch, int limit) throws IOException {
        return firstParent(branch, Optional.empty(), commit -> visible(commit.kind(), true), limit);
    }

    public List<HistoryEntry> firstParent(
            BranchName branch, UUID workspaceId, int limit) throws IOException {
        return firstParent(branch, Optional.of(Objects.requireNonNull(workspaceId, "workspaceId")),
                commit -> visible(commit.kind(), false), limit);
    }

    public List<HistoryEntry> firstParent(
            BranchName branch, UUID workspaceId, boolean includeZoneCommits, int limit)
            throws IOException {
        return firstParent(branch, Optional.of(Objects.requireNonNull(workspaceId, "workspaceId")),
                commit -> visible(commit.kind(), includeZoneCommits), limit);
    }

    public List<HistoryEntry> firstParentForZone(
            BranchName branch, UUID workspaceId, UUID zoneId, int limit) throws IOException {
        Objects.requireNonNull(zoneId, "zoneId");
        return firstParent(
                branch,
                Optional.of(Objects.requireNonNull(workspaceId, "workspaceId")),
                commit -> commit.kind() == CommitKind.ZONE
                        && commit.zoneId().filter(zoneId::equals).isPresent(),
                limit);
    }

    private List<HistoryEntry> firstParent(
            BranchName branch,
            Optional<UUID> workspaceId,
            Predicate<io.github.lumi.domain.model.Commit> included,
            int limit) throws IOException {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(included, "included");
        if (limit < 1 || limit > MAX_QUERY) {
            throw new IllegalArgumentException("History limit must be between 1 and " + MAX_QUERY);
        }
        CommitId next = refs.read(branch).orElseThrow(
                () -> new IOException("Branch does not exist: " + branch)).commit();
        ArrayList<HistoryEntry> history = new ArrayList<>(Math.min(limit, 64));
        int visited = 0;
        while (history.size() < limit && visited++ < MAX_QUERY) {
            var commit = commits.read(next);
            if (workspaceId.isPresent()
                    && !commit.workspaceId().equals(workspaceId.orElseThrow())) {
                if (history.isEmpty()) {
                    throw new IOException("Branch does not belong to workspace: " + branch);
                }
                break;
            }
            if (included.test(commit)) {
                history.add(new HistoryEntry(next, commit));
            }
            if (commit.parents().isEmpty()) {
                break;
            }
            next = commit.parents().getFirst();
        }
        return List.copyOf(history);
    }

    private static boolean visible(CommitKind kind, boolean includeZoneCommits) {
        return switch (kind) {
            case HIDDEN_SAFETY, HIDDEN_RETURN -> false;
            case ZONE -> includeZoneCommits;
            default -> true;
        };
    }
}
