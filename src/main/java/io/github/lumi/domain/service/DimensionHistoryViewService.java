package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.HistoryEntry;
import io.github.lumi.domain.model.HistoryPage;
import io.github.lumi.domain.model.Workspace;
import io.github.lumi.domain.model.Zone;
import io.github.lumi.storage.repository.CommitRepository;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Owns the bounded builder-facing read model for one dimension repository. */
public final class DimensionHistoryViewService {
    private final CommitRepository commits;
    private final HistoryQueryService history;
    private final TombstoneService tombstones;
    private final BranchService branches;
    private final WorkspaceService workspaces;
    private final ZoneService zones;
    private final AutoVersionService autoVersions;
    private final VersionDisplayNameService displayNames;
    private final VersionTagService versionTags;

    public DimensionHistoryViewService(
            CommitRepository commits,
            HistoryQueryService history,
            TombstoneService tombstones,
            BranchService branches,
            WorkspaceService workspaces,
            ZoneService zones,
            AutoVersionService autoVersions,
            VersionDisplayNameService displayNames,
            VersionTagService versionTags) {
        this.commits = Objects.requireNonNull(commits, "commits");
        this.history = Objects.requireNonNull(history, "history");
        this.tombstones = Objects.requireNonNull(tombstones, "tombstones");
        this.branches = Objects.requireNonNull(branches, "branches");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.zones = Objects.requireNonNull(zones, "zones");
        this.autoVersions = Objects.requireNonNull(autoVersions, "autoVersions");
        this.displayNames = Objects.requireNonNull(displayNames, "displayNames");
        this.versionTags = Objects.requireNonNull(versionTags, "versionTags");
    }

    public BranchRef activeBranch() throws IOException {
        return branches.active();
    }

    public Workspace activeWorkspace() throws IOException {
        return workspaces.active();
    }

    public List<Workspace> workspaces() throws IOException {
        return workspaces.list();
    }

    public List<HistoryEntry> history(int limit) throws IOException {
        return combinedHistory(activeBranch().name(), activeWorkspace(), limit);
    }

    public HistoryPage historyPage(
            BranchName branch, int offset, int limit) throws IOException {
        return historyPage(branch, offset, limit, "");
    }

    public HistoryPage historyPage(
            BranchName branch, int offset, int limit, String query)
            throws IOException {
        Workspace workspace = activeWorkspace();
        requireWorkspaceBranch(branch, workspace.id());
        String normalized = normalizeQuery(query);
        int queryLimit = normalized.isEmpty()
                ? pageQueryLimit(offset, limit) : 1_000;
        List<HistoryEntry> queried = combinedHistory(
                branch, workspace, queryLimit);
        return page(offset, limit, filter(queried, normalized));
    }

    public HistoryPage zoneHistoryPage(
            BranchName branch, UUID zoneId, int offset, int limit)
            throws IOException {
        return zoneHistoryPage(branch, zoneId, offset, limit, "");
    }

    public HistoryPage zoneHistoryPage(
            BranchName branch,
            UUID zoneId,
            int offset,
            int limit,
            String query) throws IOException {
        Workspace workspace = activeWorkspace();
        requireWorkspaceBranch(branch, workspace.id());
        zones.require(workspace.id(), zoneId);
        String normalized = normalizeQuery(query);
        int queryLimit = normalized.isEmpty()
                ? pageQueryLimit(offset, limit) : 1_000;
        return page(offset, limit, filter(
                history.firstParentForZone(
                        branch, workspace.id(), zoneId, queryLimit),
                normalized));
    }

    private List<HistoryEntry> filter(
            List<HistoryEntry> entries, String normalized) throws IOException {
        if (normalized.isEmpty()) {
            return List.copyOf(entries);
        }
        String[] tokens = normalized.split("\\s+");
        var matched = new java.util.ArrayList<HistoryEntry>();
        for (HistoryEntry entry : entries) {
            List<String> fields = List.of(
                    displayNames.read(
                            entry.id(), entry.commit().message())
                            .toLowerCase(Locale.ROOT),
                    entry.commit().author().name().toLowerCase(Locale.ROOT),
                    entry.id().hex().toLowerCase(Locale.ROOT),
                    entry.commit().kind().name().toLowerCase(Locale.ROOT),
                    String.join(" ", versionTags.read(entry.id()).values())
                            .toLowerCase(Locale.ROOT));
            boolean allMatch = java.util.Arrays.stream(tokens)
                    .allMatch(token -> fields.stream().anyMatch(field ->
                            field.contains(token)
                                    || isSubsequence(token, field)));
            if (allMatch) {
                matched.add(entry);
            }
        }
        return List.copyOf(matched);
    }

    private List<HistoryEntry> combinedHistory(
            BranchName branch, Workspace workspace, int limit)
            throws IOException {
        var visible = history.firstParent(
                branch, workspace.id(),
                !workspace.settings().hideZoneCommits(), limit);
        return Stream.concat(
                        visible.stream(),
                        autoVersions.list(branch, workspace.id(), limit).stream())
                .collect(Collectors.toMap(
                        HistoryEntry::id, entry -> entry,
                        (first, ignored) -> first))
                .values().stream()
                .sorted(java.util.Comparator.comparing(
                        (HistoryEntry entry) -> entry.commit().timestamp()).reversed())
                .limit(limit)
                .toList();
    }

    public Map<UUID, List<HistoryEntry>> zoneHistories(
            Set<UUID> zoneIds, int limit) throws IOException {
        Set<UUID> requested = Set.copyOf(zoneIds);
        Workspace workspace = activeWorkspace();
        for (UUID zoneId : requested) {
            zones.require(workspace.id(), zoneId);
        }
        return history.firstParentByZone(
                activeBranch().name(), workspace.id(), requested, limit);
    }

    public List<HistoryEntry> deletedVersions(int limit) throws IOException {
        return tombstones.deleted(activeWorkspace().id(), limit);
    }

    public List<BranchRef> branches() throws IOException {
        UUID workspaceId = activeWorkspace().id();
        var visible = new java.util.ArrayList<BranchRef>();
        for (BranchRef ref : branches.visible()) {
            if (commits.read(ref.commit()).workspaceId().equals(workspaceId)) {
                visible.add(ref);
            }
        }
        return List.copyOf(visible);
    }

    public List<Zone> zones() throws IOException {
        return zones.list(activeWorkspace().id());
    }

    private static int pageQueryLimit(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 64 || offset > 1_000 - limit) {
            throw new IllegalArgumentException(
                    "History page exceeds the bounded query window");
        }
        return Math.min(1_000, offset + limit + 1);
    }

    private static HistoryPage page(
            int offset, int limit, List<HistoryEntry> queried) {
        if (offset >= queried.size()) {
            return new HistoryPage(offset, List.of(), false);
        }
        int end = Math.min(queried.size(), offset + limit);
        return new HistoryPage(
                offset, queried.subList(offset, end), queried.size() > end);
    }

    private static String normalizeQuery(String query) {
        String normalized = Objects.requireNonNull(query, "query")
                .trim().toLowerCase(Locale.ROOT);
        if (normalized.codePointCount(0, normalized.length()) > 128
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid history search query");
        }
        return normalized;
    }

    private static boolean isSubsequence(String token, String field) {
        int matched = 0;
        for (int index = 0;
                index < field.length() && matched < token.length();
                index++) {
            if (field.charAt(index) == token.charAt(matched)) {
                matched++;
            }
        }
        return matched == token.length();
    }

    private void requireWorkspaceBranch(BranchName branch, UUID workspaceId)
            throws IOException {
        BranchRef ref = branches.visible().stream()
                .filter(candidate -> candidate.name().equals(branch))
                .findFirst()
                .orElseThrow(() -> new IOException(
                        "Branch does not exist: " + branch));
        if (!commits.read(ref.commit()).workspaceId().equals(workspaceId)) {
            throw new IOException("Branch belongs to another workspace");
        }
    }

}
