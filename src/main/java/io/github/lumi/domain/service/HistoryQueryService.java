package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.HistoryEntry;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.TombstoneRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Produces bounded immutable history views without decoding world payloads. */
public final class HistoryQueryService {
    private static final int MAX_QUERY = 1_000;
    private final CommitRepository commits;
    private final BranchRefRepository refs;
    private final TombstoneRepository tombstones;
    private final ForwardHistoryService forwardHistory;

    public HistoryQueryService(
            CommitRepository commits,
            BranchRefRepository refs,
            TombstoneRepository tombstones) {
        this.commits = Objects.requireNonNull(commits, "commits");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.tombstones = Objects.requireNonNull(tombstones, "tombstones");
        this.forwardHistory = new ForwardHistoryService(commits, refs);
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
        return firstParentByZone(branch, workspaceId, Set.of(zoneId), limit)
                .getOrDefault(zoneId, List.of());
    }

    public Map<UUID, List<HistoryEntry>> firstParentByZone(
            BranchName branch,
            UUID workspaceId,
            Set<UUID> zoneIds,
            int limit) throws IOException {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Set<UUID> requested = Set.copyOf(Objects.requireNonNull(zoneIds, "zoneIds"));
        validateLimit(limit);
        if (requested.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ArrayList<HistoryEntry>> found = new HashMap<>();
        requested.forEach(zone -> found.put(zone, new ArrayList<>(Math.min(limit, 8))));
        CommitId next = refs.read(branch).orElseThrow(
                () -> new IOException("Branch does not exist: " + branch)).commit();
        int visited = 0;
        while (visited++ < MAX_QUERY && found.values().stream()
                .anyMatch(history -> history.size() < limit)) {
            var commit = commits.read(next);
            if (!commit.workspaceId().equals(workspaceId)) {
                if (visited == 1) {
                    throw new IOException("Branch does not belong to workspace: " + branch);
                }
                break;
            }
            if (tombstones.read(next).isEmpty()
                    && commit.kind() == CommitKind.ZONE && commit.zoneId().isPresent()) {
                ArrayList<HistoryEntry> history =
                        found.get(commit.zoneId().orElseThrow());
                if (history != null && history.size() < limit) {
                    history.add(new HistoryEntry(next, commit));
                }
            }
            if (commit.parents().isEmpty()) {
                break;
            }
            next = commit.parents().getFirst();
        }
        Map<UUID, List<HistoryEntry>> result = new HashMap<>();
        found.forEach((zone, history) -> result.put(zone, List.copyOf(history)));
        return Map.copyOf(result);
    }

    private List<HistoryEntry> firstParent(
            BranchName branch,
            Optional<UUID> workspaceId,
            Predicate<io.github.lumi.domain.model.Commit> included,
            int limit) throws IOException {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(included, "included");
        validateLimit(limit);
        CommitId next = refs.read(branch).orElseThrow(
                () -> new IOException("Branch does not exist: " + branch)).commit();
        CommitId head = next;
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
            if (tombstones.read(next).isEmpty() && included.test(commit)) {
                history.add(new HistoryEntry(next, commit));
            }
            if (commit.parents().isEmpty()) {
                break;
            }
            next = commit.parents().getFirst();
        }
        Map<CommitId, HistoryEntry> combined = new LinkedHashMap<>();
        history.forEach(entry -> combined.put(entry.id(), entry));
        for (CommitId root : forwardHistory.roots(branch, workspaceId)) {
            if (visited >= MAX_QUERY) {
                break;
            }
            ArrayList<HistoryEntry> forward = new ArrayList<>();
            next = root;
            boolean reachesHead = false;
            while (visited++ < MAX_QUERY) {
                if (next.equals(head)) {
                    reachesHead = true;
                    break;
                }
                var commit = commits.read(next);
                if (workspaceId.isPresent()
                        && !commit.workspaceId().equals(workspaceId.orElseThrow())) {
                    break;
                }
                if (tombstones.read(next).isEmpty() && included.test(commit)) {
                    forward.add(new HistoryEntry(next, commit));
                }
                if (commit.parents().isEmpty()) {
                    break;
                }
                next = commit.parents().getFirst();
            }
            if (reachesHead) {
                forward.forEach(entry -> combined.putIfAbsent(entry.id(), entry));
            }
        }
        if (combined.size() == history.size()) {
            return List.copyOf(history);
        }
        return combined.values().stream()
                .sorted(Comparator
                        .comparing((HistoryEntry entry) -> entry.commit().timestamp()).reversed()
                        .thenComparing(entry -> entry.id().hex()))
                .limit(limit)
                .toList();
    }

    private static boolean visible(CommitKind kind, boolean includeZoneCommits) {
        return switch (kind) {
            case HIDDEN_SAFETY, HIDDEN_RETURN -> false;
            case ZONE -> includeZoneCommits;
            default -> true;
        };
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_QUERY) {
            throw new IllegalArgumentException(
                    "History limit must be between 1 and " + MAX_QUERY);
        }
    }
}
