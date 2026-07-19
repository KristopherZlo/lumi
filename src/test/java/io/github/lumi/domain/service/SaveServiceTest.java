package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.PlayerSpawn;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.MerkleTreeEditor;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.VersionTagRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import io.github.lumi.storage.object.ObjectStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveServiceTest {
    @TempDir
    Path repositoryRoot;

    @Test
    void publishesCapturedStateAsChildCommit() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var rootTree = objects.write(new DimensionTree(Map.of()));
        var initialId = commits.write(commit(rootTree, List.of(), "Initial"));
        var initialRef = refs.create(new BranchName("main"), initialId);
        UUID player = UUID.fromString("30000000-0000-0000-0000-000000000003");
        PlayerSpawn spawn = new PlayerSpawn(10, 70, -20, 180.0F, 0.0F, true);
        SectionKey key = new SectionKey(0, 0, 0);
        CapturedWorldState captured = new CapturedWorldState(
                Map.of(key, airSection()), Map.of(),
                new WorkingIndexSnapshot(Map.of(key, 3L)),
                new CommitStatistics(1, 0, 1, 0), Map.of(player, spawn));
        VersionTagRepository tags = new VersionTagRepository(repositoryRoot);
        SaveService service = new SaveService(objects, new MerkleTreeEditor(objects), commits, refs,
                new OperationJournalRepository(repositoryRoot), tags);
        var progress = new ArrayList<SavePublicationProgress>();

        SaveResult result = service.save(new SaveRequest(
                initialRef, author(), "Tower", Instant.parse("2026-07-15T12:00:00Z"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                Optional.empty(), CommitKind.MANUAL,
                VersionTags.parse("#tower, stone")), captured, progress::add);

        Commit saved = commits.read(result.commitId());
        assertEquals(List.of(initialId), saved.parents());
        assertEquals(result.branchRef(), refs.read(new BranchName("main")).orElseThrow());
        assertEquals(captured.generations(), result.capturedGenerations());
        assertEquals(Map.of(player, spawn), saved.playerSpawns());
        assertEquals(VersionTags.parse("tower, stone"), tags.read(result.commitId()));
        assertTrue(new OperationJournalRepository(repositoryRoot).read().isEmpty());
        assertTrue(progress.contains(new SavePublicationProgress(
                "Save: writing captured state", 1, 1)));
        assertTrue(progress.contains(new SavePublicationProgress(
                "Save: building history tree", 3, 3)));
        assertTrue(progress.stream().anyMatch(value ->
                value.phase().equals("Save: writing version tags")));
        assertEquals("Save: publishing branch", progress.getLast().phase());
        try (var files = Files.walk(repositoryRoot.resolve("objects").resolve("packs"))) {
            assertEquals(1, files.filter(path -> path.toString().endsWith(".pack")).count());
        }
    }

    @Test
    void permitsEmptyNamedCommitWithoutNewWorldObjects() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        var initialId = commits.write(commit(tree, List.of(), "Initial"));
        var initialRef = refs.create(new BranchName("main"), initialId);
        SaveService service = new SaveService(objects, new MerkleTreeEditor(objects), commits, refs,
                new OperationJournalRepository(repositoryRoot),
                new VersionTagRepository(repositoryRoot));

        SaveResult result = service.save(new SaveRequest(
                initialRef, author(), "Milestone", Instant.parse("2026-07-15T12:00:00Z"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                Optional.empty(), CommitKind.MANUAL),
                new CapturedWorldState(Map.of(), Map.of(), WorkingIndexSnapshot.empty(),
                        new CommitStatistics(0, 0, 0, 0)));

        assertNotEquals(initialId, result.commitId());
        assertEquals(tree, commits.read(result.commitId()).tree());
    }

    @Test
    void rejectsSaveIntoABranchOwnedByAnotherWorkspace() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        var initialId = commits.write(commit(tree, List.of(), "Initial"));
        var initialRef = refs.create(new BranchName("main"), initialId);
        SaveService service = new SaveService(
                objects, new MerkleTreeEditor(objects), commits, refs,
                new OperationJournalRepository(repositoryRoot),
                new VersionTagRepository(repositoryRoot));
        var request = new SaveRequest(
                initialRef, author(), "Wrong project", Instant.EPOCH,
                UUID.fromString("90000000-0000-0000-0000-000000000009"),
                Optional.empty(), CommitKind.MANUAL);

        assertThrows(IOException.class, () -> service.save(
                request, new CapturedWorldState(
                        Map.of(), Map.of(), WorkingIndexSnapshot.empty(),
                        new CommitStatistics(0, 0, 0, 0))));

        assertEquals(initialRef, refs.read(initialRef.name()).orElseThrow());
        try (var files = Files.walk(repositoryRoot.resolve("commits"))) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void leavesTheRefUnchangedWhenVersionTagsCannotPersist() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        var initialId = commits.write(commit(tree, List.of(), "Initial"));
        var initialRef = refs.create(new BranchName("main"), initialId);
        Files.write(repositoryRoot.resolve("tags"), new byte[] {1});
        SaveService service = new SaveService(
                objects, new MerkleTreeEditor(objects), commits, refs,
                new OperationJournalRepository(repositoryRoot),
                new VersionTagRepository(repositoryRoot));
        var request = new SaveRequest(
                initialRef, author(), "Tagged", Instant.EPOCH,
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                Optional.empty(), CommitKind.MANUAL, VersionTags.parse("roof"));

        assertThrows(IOException.class, () -> service.save(
                request, new CapturedWorldState(
                        Map.of(), Map.of(), WorkingIndexSnapshot.empty(),
                        new CommitStatistics(0, 0, 0, 0))));

        assertEquals(initialRef, refs.read(initialRef.name()).orElseThrow());
        assertTrue(new OperationJournalRepository(repositoryRoot).read().isEmpty());
    }

    @Test
    void amendReplacesHeadWithItsParents() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        var root = commits.write(commit(tree, List.of(), "Initial"));
        var previous = commits.write(commit(tree, List.of(root), "Previous"));
        var ref = refs.create(new BranchName("main"), previous);
        SaveService service = new SaveService(objects, new MerkleTreeEditor(objects), commits, refs,
                new OperationJournalRepository(repositoryRoot),
                new VersionTagRepository(repositoryRoot));

        SaveResult result = service.save(new SaveRequest(
                ref, author(), "Replacement", Instant.parse("2026-07-15T12:00:00Z"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                Optional.empty(), CommitKind.AMEND),
                new CapturedWorldState(Map.of(), Map.of(), WorkingIndexSnapshot.empty(),
                        new CommitStatistics(0, 0, 0, 0)));

        assertEquals(List.of(root), commits.read(result.commitId()).parents());
        assertNotEquals(previous, result.commitId());
        assertEquals(result.commitId(), refs.read(new BranchName("main")).orElseThrow().commit());
    }

    @Test
    void zoneAmendReplacesHeadButRemainsInZoneHistory() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        var root = commits.write(commit(tree, List.of(), "Initial"));
        var previous = commits.write(commit(tree, List.of(root), "Previous"));
        var ref = refs.create(new BranchName("main"), previous);
        SaveService service = new SaveService(objects, new MerkleTreeEditor(objects), commits, refs,
                new OperationJournalRepository(repositoryRoot),
                new VersionTagRepository(repositoryRoot));
        UUID zone = new UUID(0, 17);

        SaveResult result = service.save(new SaveRequest(
                ref, author(), "Zone replacement", Instant.EPOCH,
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                Optional.of(zone), CommitKind.ZONE, VersionTags.parse("clock"), true),
                new CapturedWorldState(Map.of(), Map.of(), WorkingIndexSnapshot.empty(),
                        new CommitStatistics(0, 0, 0, 0)));

        Commit amended = commits.read(result.commitId());
        assertEquals(List.of(root), amended.parents());
        assertEquals(CommitKind.ZONE, amended.kind());
        assertEquals(Optional.of(zone), amended.zoneId());
        assertEquals(VersionTags.parse("clock"),
                new VersionTagRepository(repositoryRoot).read(result.commitId()));
    }

    @Test
    void createsHiddenCheckpointWithoutMovingTheSourceBranch() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var rootTree = objects.write(new DimensionTree(Map.of()));
        var initialId = commits.write(commit(rootTree, List.of(), "Initial"));
        var initialRef = refs.create(new BranchName("main"), initialId);
        SectionKey key = new SectionKey(0, 0, 0);
        var captured = new CapturedWorldState(
                Map.of(key, airSection()), Map.of(),
                new WorkingIndexSnapshot(Map.of(key, 3L)),
                new CommitStatistics(1, 0, 1, 0));
        SaveService service = new SaveService(objects, new MerkleTreeEditor(objects), commits, refs,
                new OperationJournalRepository(repositoryRoot),
                new VersionTagRepository(repositoryRoot));
        var request = new SaveRequest(
                initialRef, author(), "Partial Restore checkpoint", Instant.EPOCH,
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                Optional.empty(), CommitKind.HIDDEN_RETURN);
        assertEquals(VersionTags.empty(), request.tags());
        assertThrows(IllegalArgumentException.class, () -> new SaveRequest(
                initialRef, author(), "Invalid tagged checkpoint", Instant.EPOCH,
                request.workspaceId(), Optional.empty(), CommitKind.HIDDEN_RETURN,
                VersionTags.parse("internal")));

        SaveResult result = service.checkpoint(
                request, captured, new BranchName("hidden/return/test"));

        assertEquals(initialRef, refs.read(initialRef.name()).orElseThrow());
        assertEquals(result.branchRef(), refs.read(result.branchRef().name()).orElseThrow());
        assertEquals(result.commitId(), result.branchRef().commit());
        assertEquals(captured.generations(), result.capturedGenerations());
    }

    @Test
    void oneHundredOneBlockSavesGrowByUniqueStatesNotSaveCount() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var rootTree = objects.write(new DimensionTree(Map.of()));
        var initialId = commits.write(commit(rootTree, List.of(), "Initial"));
        var current = refs.create(new BranchName("main"), initialId);
        SaveService service = new SaveService(
                objects, new MerkleTreeEditor(objects), commits, refs,
                new OperationJournalRepository(repositoryRoot),
                new VersionTagRepository(repositoryRoot));
        SectionKey key = new SectionKey(0, 0, 0);
        UUID workspace = UUID.fromString("20000000-0000-0000-0000-000000000002");

        for (int index = 0; index < 100; index++) {
            SectionBlob section = oneBlockSection("test:state_" + index % 10);
            long generation = index + 1L;
            var captured = new CapturedWorldState(
                    Map.of(key, section), Map.of(),
                    new WorkingIndexSnapshot(Map.of(key, generation)),
                    new CommitStatistics(1, 0, 1, 0));
            current = service.save(new SaveRequest(
                    current, author(), "Save " + index,
                    Instant.EPOCH.plusSeconds(index + 1L), workspace,
                    Optional.empty(), CommitKind.MANUAL), captured).branchRef();
        }

        assertEquals(41, new ObjectStore(repositoryRoot.resolve("objects"))
                .listIds().size());
        try (var files = Files.walk(repositoryRoot.resolve("commits"))) {
            assertEquals(101, files.filter(Files::isRegularFile).count());
        }
    }

    private static Commit commit(io.github.lumi.domain.model.ObjectId tree,
            List<io.github.lumi.domain.model.CommitId> parents, String message) {
        return new Commit(tree, parents, author(), message, Instant.EPOCH,
                UUID.fromString("20000000-0000-0000-0000-000000000002"), Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(0, 0, 0, 0));
    }

    private static CommitAuthor author() {
        return new CommitAuthor(UUID.fromString("10000000-0000-0000-0000-000000000001"), "Builder");
    }

    private static SectionBlob airSection() {
        return new SectionBlob(
                new ArrayList<>(Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:air")), Map.of());
    }

    private static SectionBlob oneBlockSection(String state) {
        var states = new ArrayList<>(
                Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:air"));
        states.set(0, state);
        return new SectionBlob(states, Map.of());
    }
}
