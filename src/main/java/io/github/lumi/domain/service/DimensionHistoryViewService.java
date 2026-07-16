package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.HistoryEntry;
import io.github.lumi.domain.model.Workspace;
import io.github.lumi.domain.model.Zone;
import io.github.lumi.storage.repository.CommitRepository;
import java.io.IOException;
import java.util.List;
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

    public DimensionHistoryViewService(
            CommitRepository commits,
            HistoryQueryService history,
            TombstoneService tombstones,
            BranchService branches,
            WorkspaceService workspaces,
            ZoneService zones,
            AutoVersionService autoVersions) {
        this.commits = Objects.requireNonNull(commits, "commits");
        this.history = Objects.requireNonNull(history, "history");
        this.tombstones = Objects.requireNonNull(tombstones, "tombstones");
        this.branches = Objects.requireNonNull(branches, "branches");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.zones = Objects.requireNonNull(zones, "zones");
        this.autoVersions = Objects.requireNonNull(autoVersions, "autoVersions");
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
        BranchRef ref = activeBranch();
        Workspace workspace = activeWorkspace();
        var visible = history.firstParent(
                ref.name(), workspace.id(),
                !workspace.settings().hideZoneCommits(), limit);
        return Stream.concat(
                        visible.stream(),
                        autoVersions.list(ref.name(), workspace.id(), limit).stream())
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

}
