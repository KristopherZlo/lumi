package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkspaceSettings;
import io.github.lumi.storage.repository.ActiveBranchRepository;
import io.github.lumi.storage.repository.ActiveWorkspaceRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.TombstoneRepository;
import io.github.lumi.storage.repository.VersionDisplayNameRepository;
import io.github.lumi.storage.repository.VersionTagRepository;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorkspaceRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import io.github.lumi.storage.repository.ZoneRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DimensionHistoryViewServiceTest {
    @TempDir Path repository;

    @Test
    void exposesOnlyTheActiveWorkspaceHistoryBranchesAndZones() throws Exception {
        UUID workspaceId = new UUID(0, 2);
        UUID foreignWorkspaceId = new UUID(0, 3);
        UUID zoneId = new UUID(0, 4);
        var commits = new CommitRepository(repository);
        var refs = new BranchRefRepository(repository);
        var active = new ActiveBranchRepository(repository);
        var objects = new WorldObjectRepository(repository);
        var main = new DimensionHistoryInitializer(objects, commits, refs, active)
                .initialize(workspaceId);
        var workspaces = new WorkspaceService(
                new WorkspaceRepository(repository),
                new ActiveWorkspaceRepository(repository), commits, refs);
        workspaces.initializeDefault(workspaceId);
        var branches = new BranchService(
                commits, refs, active, new WorkingIndexRepository(repository));
        var zones = new ZoneService(new ZoneRepository(repository));
        zones.create(zoneId, workspaceId, "Clock", 0x44AAFF,
                Set.of(new SectionKey(0, 4, 0)));

        var tree = objects.write(new DimensionTree(Map.of()));
        var manual = commits.write(commit(
                tree, List.of(main.commit()), workspaceId, Optional.empty(),
                CommitKind.MANUAL, "Manual", 10));
        var zone = commits.write(commit(
                tree, List.of(manual), workspaceId, Optional.of(zoneId),
                CommitKind.ZONE, "Zone", 20));
        refs.compareAndSet(main, zone);
        refs.create(new BranchName("idea"), manual);
        var autoVersions = new AutoVersionService(commits, refs);
        var automatic = commits.write(commit(
                tree, List.of(zone), workspaceId, Optional.empty(),
                CommitKind.AUTO, "Auto", 30));
        refs.create(autoVersions.refName(new BranchName("main"), new UUID(0, 5)), automatic);
        var foreign = commits.write(commit(
                tree, List.of(), foreignWorkspaceId, Optional.empty(),
                CommitKind.MANUAL, "Foreign", 40));
        refs.create(new BranchName("foreign"), foreign);
        var displayNames = new VersionDisplayNameService(
                commits, new VersionDisplayNameRepository(repository));
        var versionTags = new VersionTagService(
                commits, new VersionTagRepository(repository));
        displayNames.replace(
                manual, workspaceId,
                new io.github.lumi.domain.model.VersionDisplayName(
                        "Golden tower"));
        versionTags.replace(
                manual, workspaceId,
                new io.github.lumi.domain.model.VersionTags(
                        List.of("clockwork")));

        var view = new DimensionHistoryViewService(
                commits,
                new HistoryQueryService(
                        commits, refs, new TombstoneRepository(repository)),
                new TombstoneService(
                        commits, refs, new TombstoneRepository(repository)),
                branches, workspaces, zones, autoVersions,
                displayNames, versionTags);

        assertEquals(workspaceId, view.activeWorkspace().id());
        assertEquals(List.of(workspaceId),
                view.workspaces().stream().map(workspace -> workspace.id()).toList());
        assertEquals(List.of(manual, main.commit()),
                view.history(10).stream().map(entry -> entry.id()).toList());
        var historyPage = view.historyPage(new BranchName("main"), 0, 1, "");
        assertEquals(List.of(manual),
                historyPage.entries().stream().map(entry -> entry.id()).toList());
        assertEquals(true, historyPage.hasMore());
        workspaces.updateActiveSettings(
                new WorkspaceSettings(true, true, true, true, true));
        assertEquals(List.of(automatic, manual, main.commit()),
                view.history(10).stream().map(entry -> entry.id()).toList());
        assertEquals(List.of(manual),
                view.historyPage(
                        new BranchName("main"), 0, 1,
                        "gold clkwrk")
                        .entries().stream().map(entry -> entry.id()).toList());
        assertEquals(List.of(zone),
                view.zoneHistories(Set.of(zoneId), 10).get(zoneId).stream()
                        .map(entry -> entry.id()).toList());
        assertEquals(List.of(zone),
                view.zoneHistoryPage(new BranchName("main"), zoneId, 0, 1, "")
                        .entries().stream().map(entry -> entry.id()).toList());
        assertEquals(List.of(new BranchName("main")),
                view.zoneHistory(new BranchName("main"), zoneId, 0, 1, "")
                        .branches().stream().map(ref -> ref.name()).toList());
        assertEquals(List.of(new BranchName("idea"), new BranchName("main")),
                view.branches().stream().map(ref -> ref.name()).toList());
        assertEquals(List.of(zoneId),
                view.zones().stream().map(visible -> visible.id()).toList());
        assertEquals(List.of(), view.deletedVersions(10));
    }

    private static Commit commit(
            io.github.lumi.domain.model.ObjectId tree,
            List<io.github.lumi.domain.model.CommitId> parents,
            UUID workspaceId,
            Optional<UUID> zoneId,
            CommitKind kind,
            String message,
            long second) {
        return new Commit(
                tree, parents, new CommitAuthor(new UUID(0, 1), "Builder"),
                message, Instant.ofEpochSecond(second), workspaceId, zoneId,
                kind, new CommitStatistics(0, 0, 0, 0));
    }
}
