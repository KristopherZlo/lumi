package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.MerkleTreeEditor;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
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
        SectionKey key = new SectionKey(0, 0, 0);
        CapturedWorldState captured = new CapturedWorldState(
                Map.of(key, airSection()), Map.of(),
                new WorkingIndexSnapshot(Map.of(key, 3L)),
                new CommitStatistics(1, 0, 1, 0));
        SaveService service = new SaveService(objects, new MerkleTreeEditor(objects), commits, refs,
                new OperationJournalRepository(repositoryRoot));

        SaveResult result = service.save(new SaveRequest(
                initialRef, author(), "Tower", Instant.parse("2026-07-15T12:00:00Z"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                Optional.empty(), CommitKind.MANUAL), captured);

        Commit saved = commits.read(result.commitId());
        assertEquals(List.of(initialId), saved.parents());
        assertEquals(result.branchRef(), refs.read(new BranchName("main")).orElseThrow());
        assertEquals(captured.generations(), result.capturedGenerations());
        assertTrue(new OperationJournalRepository(repositoryRoot).read().isEmpty());
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
                new OperationJournalRepository(repositoryRoot));

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
                new OperationJournalRepository(repositoryRoot));
        var request = new SaveRequest(
                initialRef, author(), "Partial Restore checkpoint", Instant.EPOCH,
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                Optional.empty(), CommitKind.HIDDEN_RETURN);

        SaveResult result = service.checkpoint(
                request, captured, new BranchName("hidden/return/test"));

        assertEquals(initialRef, refs.read(initialRef.name()).orElseThrow());
        assertEquals(result.branchRef(), refs.read(result.branchRef().name()).orElseThrow());
        assertEquals(result.commitId(), result.branchRef().commit());
        assertEquals(captured.generations(), result.capturedGenerations());
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
}
