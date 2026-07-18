package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.HistoryEntry;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.TombstoneRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryQueryServiceTest {
    @TempDir Path repositoryRoot;

    @Test
    void returnsBoundedNewestFirstFirstParentHistory() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        CommitId first = commits.write(commit(tree, List.of(), "First", 1));
        CommitId second = commits.write(commit(tree, List.of(first), "Second", 2));
        CommitId third = commits.write(commit(tree, List.of(second), "Third", 3));
        refs.create(new BranchName("main"), third);

        List<HistoryEntry> history = query(commits, refs)
                .firstParent(new BranchName("main"), 2);

        assertEquals(List.of(third, second), history.stream().map(HistoryEntry::id).toList());
        assertEquals(List.of("Third", "Second"),
                history.stream().map(entry -> entry.commit().message()).toList());
    }

    @Test
    void keepsForwardDescendantsVisibleAfterRestoreReset() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        CommitId first = commits.write(commit(tree, List.of(), "First", 1));
        CommitId second = commits.write(commit(tree, List.of(first), "Second", 2));
        CommitId third = commits.write(commit(tree, List.of(second), "Third", 3));
        var main = refs.create(new BranchName("main"), third);
        CommitId checkpoint = commits.write(new Commit(
                tree, List.of(third), new CommitAuthor(new UUID(0, 1), "Builder"),
                "Return point", Instant.ofEpochSecond(4), new UUID(0, 2), Optional.empty(),
                CommitKind.HIDDEN_RETURN, new CommitStatistics(0, 0, 0, 0)));
        var checkpointRef = refs.compareAndSet(main, checkpoint);
        refs.create(new BranchName("hidden/return/test"), checkpoint);
        refs.compareAndSet(checkpointRef, first);

        List<HistoryEntry> history = query(commits, refs)
                .firstParent(new BranchName("main"), new UUID(0, 2), 10);

        assertEquals(List.of(third, second, first),
                history.stream().map(HistoryEntry::id).toList());
    }

    @Test
    void stopsBeforeParentFromAnotherWorkspace() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        UUID sourceWorkspace = new UUID(0, 2);
        UUID namedWorkspace = new UUID(0, 3);
        CommitId source = commits.write(commit(
                tree, List.of(), "Source", 1, sourceWorkspace));
        CommitId root = commits.write(commit(
                tree, List.of(source), "Initial workspace", 2, namedWorkspace));
        refs.create(new BranchName("workspace/named/main"), root);

        List<HistoryEntry> history = query(commits, refs)
                .firstParent(new BranchName("workspace/named/main"), namedWorkspace, 10);

        assertEquals(List.of(root), history.stream().map(HistoryEntry::id).toList());
    }

    @Test
    void hidesZoneCommitsFromWorkspaceHistoryByDefault() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        UUID workspace = new UUID(0, 2);
        CommitId manual = commits.write(commit(tree, List.of(), "Manual", 1));
        CommitId zone = commits.write(new Commit(
                tree, List.of(manual), new CommitAuthor(new UUID(0, 1), "Builder"), "Zone",
                Instant.ofEpochSecond(2), workspace, Optional.of(new UUID(0, 4)),
                CommitKind.ZONE, new CommitStatistics(0, 0, 0, 0)));
        BranchName branch = new BranchName("main");
        refs.create(branch, zone);
        HistoryQueryService query = query(commits, refs);

        assertEquals(List.of(manual), query.firstParent(branch, workspace, 10).stream()
                .map(HistoryEntry::id).toList());
        assertEquals(List.of(zone, manual), query.firstParent(branch, workspace, true, 10).stream()
                .map(HistoryEntry::id).toList());
    }

    @Test
    void returnsOnlyCommitsForTheRequestedZone() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        UUID workspace = new UUID(0, 2);
        UUID clock = new UUID(0, 4);
        UUID door = new UUID(0, 5);
        CommitId manual = commits.write(commit(tree, List.of(), "Manual", 1));
        CommitId clockSave = commits.write(zoneCommit(
                tree, List.of(manual), workspace, clock, "Clock", 2));
        CommitId doorSave = commits.write(zoneCommit(
                tree, List.of(clockSave), workspace, door, "Door", 3));
        BranchName branch = new BranchName("main");
        refs.create(branch, doorSave);

        assertEquals(List.of(clockSave), query(commits, refs)
                .firstParentForZone(branch, workspace, clock, 10).stream()
                .map(HistoryEntry::id).toList());
        var histories = query(commits, refs)
                .firstParentByZone(branch, workspace, Set.of(clock, door), 10);
        assertEquals(List.of(clockSave), histories.get(clock).stream()
                .map(HistoryEntry::id).toList());
        assertEquals(List.of(doorSave), histories.get(door).stream()
                .map(HistoryEntry::id).toList());
    }

    @Test
    void neverShowsInternalSafetyCommitsInBuilderHistory() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        UUID workspace = new UUID(0, 2);
        CommitId hidden = commits.write(new Commit(
                tree, List.of(), new CommitAuthor(new UUID(0, 1), "Builder"), "Checkpoint",
                Instant.EPOCH, workspace, Optional.empty(), CommitKind.HIDDEN_RETURN,
                new CommitStatistics(0, 0, 0, 0)));
        CommitId manual = commits.write(commit(tree, List.of(hidden), "Visible", 1));
        BranchName branch = new BranchName("main");
        refs.create(branch, manual);

        assertEquals(List.of(manual), query(commits, refs)
                .firstParent(branch, workspace, true, 10).stream()
                .map(HistoryEntry::id).toList());
    }

    @Test
    void hidesTombstonedCommitButContinuesThroughItsParent() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        CommitId first = commits.write(commit(tree, List.of(), "First", 1));
        CommitId deleted = commits.write(commit(tree, List.of(first), "Deleted", 2));
        refs.create(new BranchName("main"), deleted);
        new TombstoneRepository(repositoryRoot).create(
                new io.github.lumi.domain.model.CommitTombstone(
                        deleted, new CommitAuthor(new UUID(0, 1), "Builder"), Instant.EPOCH));

        assertEquals(List.of(first), query(commits, refs)
                .firstParent(new BranchName("main"), 10).stream()
                .map(HistoryEntry::id).toList());
    }

    private HistoryQueryService query(
            CommitRepository commits, BranchRefRepository refs) {
        return new HistoryQueryService(
                commits, refs, new TombstoneRepository(repositoryRoot));
    }

    private static Commit commit(
            io.github.lumi.domain.model.ObjectId tree,
            List<CommitId> parents,
            String message,
            long second) {
        return new Commit(
                tree, parents, new CommitAuthor(new UUID(0, 1), "Builder"), message,
                Instant.ofEpochSecond(second), new UUID(0, 2), Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(0, 0, 0, 0));
    }

    private static Commit commit(
            io.github.lumi.domain.model.ObjectId tree,
            List<CommitId> parents,
            String message,
            long second,
            UUID workspace) {
        return new Commit(
                tree, parents, new CommitAuthor(new UUID(0, 1), "Builder"), message,
                Instant.ofEpochSecond(second), workspace, Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(0, 0, 0, 0));
    }

    private static Commit zoneCommit(
            io.github.lumi.domain.model.ObjectId tree,
            List<CommitId> parents,
            UUID workspace,
            UUID zone,
            String message,
            long second) {
        return new Commit(
                tree, parents, new CommitAuthor(new UUID(0, 1), "Builder"), message,
                Instant.ofEpochSecond(second), workspace, Optional.of(zone),
                CommitKind.ZONE, new CommitStatistics(0, 0, 0, 0));
    }
}
