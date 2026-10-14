package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.TombstoneRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TombstoneServiceTest {
    @TempDir Path repositoryRoot;

    @Test
    void softDeleteMovesEveryPointingHeadAndCleanupReleasesMarker() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        TombstoneRepository tombstones = new TombstoneRepository(repositoryRoot);
        UUID workspace = new UUID(0, 2);
        var tree = new WorldObjectRepository(repositoryRoot)
                .write(new DimensionTree(Map.of()));
        CommitId parent = commits.write(commit(tree, List.of(), workspace, "Parent"));
        CommitId deleted = commits.write(commit(tree, List.of(parent), workspace, "Deleted"));
        refs.create(new BranchName("main"), deleted);
        refs.create(new BranchName("idea"), deleted);
        BranchName auto = new BranchName("hidden/auto/branch/version");
        refs.create(auto, deleted);
        TombstoneService service = new TombstoneService(commits, refs, tombstones);

        service.softDelete(
                deleted, workspace, new CommitAuthor(new UUID(0, 7), "Builder"),
                Instant.parse("2026-07-16T12:00:00Z"));

        assertEquals(parent, refs.read(new BranchName("main")).orElseThrow().commit());
        assertEquals(parent, refs.read(new BranchName("idea")).orElseThrow().commit());
        assertTrue(refs.read(auto).isEmpty());
        assertTrue(tombstones.contains(deleted));
        assertEquals(List.of(deleted), service.deleted(workspace, 10).stream()
                .map(io.github.lumi.domain.model.HistoryEntry::id).toList());

        service.cleanup(deleted, workspace);

        assertEquals(false, tombstones.contains(deleted));
    }

    @Test
    void refusesCleanupWhileAVisibleDescendantStillRequiresTheCommit() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        TombstoneRepository tombstones = new TombstoneRepository(repositoryRoot);
        UUID workspace = new UUID(0, 2);
        var tree = new WorldObjectRepository(repositoryRoot)
                .write(new DimensionTree(Map.of()));
        CommitId root = commits.write(commit(tree, List.of(), workspace, "Root"));
        CommitId deleted = commits.write(commit(tree, List.of(root), workspace, "Deleted"));
        CommitId child = commits.write(commit(tree, List.of(deleted), workspace, "Child"));
        refs.create(new BranchName("main"), child);
        TombstoneService service = new TombstoneService(commits, refs, tombstones);
        service.softDelete(
                deleted, workspace, new CommitAuthor(new UUID(0, 7), "Builder"),
                Instant.EPOCH);

        assertThrows(IllegalStateException.class,
                () -> service.cleanup(deleted, workspace));
        assertTrue(tombstones.contains(deleted));
    }

    @Test
    void restoreReturnsADeletedLeafToItsPreviousBranchHead() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        TombstoneRepository tombstones = new TombstoneRepository(repositoryRoot);
        UUID workspace = new UUID(0, 2);
        var tree = new WorldObjectRepository(repositoryRoot)
                .write(new DimensionTree(Map.of()));
        CommitId parent = commits.write(commit(tree, List.of(), workspace, "Parent"));
        CommitId deleted = commits.write(commit(tree, List.of(parent), workspace, "Deleted"));
        BranchName main = new BranchName("main");
        refs.create(main, deleted);
        TombstoneService service = new TombstoneService(commits, refs, tombstones);

        service.softDelete(deleted, workspace,
                new CommitAuthor(new UUID(0, 7), "Builder"), Instant.EPOCH);
        service.restore(deleted, workspace);

        assertEquals(deleted, refs.read(main).orElseThrow().commit());
        assertFalse(tombstones.contains(deleted));
    }

    @Test
    void restoreUsesANewBranchWhenTheOriginalBranchAdvanced() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        TombstoneRepository tombstones = new TombstoneRepository(repositoryRoot);
        UUID workspace = new UUID(0, 2);
        var tree = new WorldObjectRepository(repositoryRoot)
                .write(new DimensionTree(Map.of()));
        CommitId parent = commits.write(commit(tree, List.of(), workspace, "Parent"));
        CommitId deleted = commits.write(commit(tree, List.of(parent), workspace, "Deleted"));
        CommitId advanced = commits.write(commit(tree, List.of(parent), workspace, "Advanced"));
        BranchName main = new BranchName("main");
        refs.create(main, deleted);
        TombstoneService service = new TombstoneService(commits, refs, tombstones);
        service.softDelete(deleted, workspace,
                new CommitAuthor(new UUID(0, 7), "Builder"), Instant.EPOCH);
        refs.compareAndSet(refs.read(main).orElseThrow(), advanced);

        service.restore(deleted, workspace);

        assertEquals(advanced, refs.read(main).orElseThrow().commit());
        BranchName restored = WorkspaceService.branchName(
                workspace, new BranchName("restored-" + deleted.hex().substring(0, 8)));
        assertEquals(deleted, refs.read(restored).orElseThrow().commit());
    }

    @Test
    void refusesToDeleteRootWhileABranchPointsToIt() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        UUID workspace = new UUID(0, 2);
        var tree = new WorldObjectRepository(repositoryRoot)
                .write(new DimensionTree(Map.of()));
        CommitId root = commits.write(commit(tree, List.of(), workspace, "Root"));
        refs.create(new BranchName("main"), root);
        TombstoneService service = new TombstoneService(
                commits, refs, new TombstoneRepository(repositoryRoot));

        assertThrows(IllegalStateException.class, () -> service.softDelete(
                root, workspace, new CommitAuthor(new UUID(0, 7), "Builder"),
                Instant.EPOCH));
    }

    private static Commit commit(
            io.github.lumi.domain.model.ObjectId tree,
            List<CommitId> parents,
            UUID workspace,
            String message) {
        return new Commit(
                tree, parents, new CommitAuthor(new UUID(0, 1), "Builder"), message,
                Instant.EPOCH, workspace, Optional.empty(), CommitKind.MANUAL,
                new CommitStatistics(0, 0, 0, 0));
    }
}
