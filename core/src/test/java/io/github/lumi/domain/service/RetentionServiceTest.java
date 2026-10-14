package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionServiceTest {
    private static final List<String> CHECKPOINT_PREFIXES = List.of(
            "hidden/return/", "hidden/partial/", "hidden/zone/",
            "hidden/branch-switch/", "hidden/rollback/");
    @TempDir Path repositoryRoot;

    @Test
    void boundsOperationCheckpointsAndProtectsTheCurrentPublication() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var tree = new WorldObjectRepository(repositoryRoot)
                .write(new DimensionTree(Map.of()));
        CommitId visible = commits.write(commit(tree, CommitKind.MANUAL, 0));
        refs.create(new BranchName("main"), visible);
        for (int index = 0; index < 19; index++) {
            String prefix = CHECKPOINT_PREFIXES.get(index % CHECKPOINT_PREFIXES.size());
            refs.create(new BranchName(prefix + index), visible);
        }
        BranchRef protectedRef = refs.create(
                new BranchName("hidden/zone/zz-protected"), visible);
        refs.create(new BranchName("hidden/auto/main/version"), visible);
        refs.create(new BranchName("hidden/forward/main/version"), visible);
        refs.create(new BranchName("hidden/deleted/version/main"), visible);
        refs.create(new BranchName("hidden/session-undo/action"), visible);

        int deleted = new RetentionService(commits, refs)
                .pruneAfterPublication(16, protectedRef);

        assertEquals(4, deleted);
        assertTrue(refs.read(new BranchName("main")).isPresent());
        assertEquals(16, refs.list().stream()
                .filter(ref -> CHECKPOINT_PREFIXES.stream().anyMatch(
                        prefix -> ref.name().value().startsWith(prefix)))
                .count());
        assertTrue(refs.read(protectedRef.name()).isPresent());
        assertTrue(refs.read(new BranchName("hidden/auto/main/version")).isPresent());
        assertTrue(refs.read(new BranchName("hidden/forward/main/version")).isPresent());
        assertTrue(refs.read(new BranchName("hidden/deleted/version/main")).isPresent());
        assertTrue(refs.read(new BranchName("hidden/session-undo/action")).isPresent());
    }

    @Test
    void removesTheOldestUnprotectedCheckpointFirst() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var tree = new WorldObjectRepository(repositoryRoot)
                .write(new DimensionTree(Map.of()));
        CommitId old = commits.write(commit(tree, CommitKind.MANUAL, 1));
        CommitId middle = commits.write(commit(tree, CommitKind.MANUAL, 2));
        CommitId newest = commits.write(commit(tree, CommitKind.MANUAL, 3));
        BranchRef published = refs.create(
                new BranchName("hidden/return/c-current"), newest);
        refs.create(new BranchName("hidden/return/a-old"), old);
        refs.create(new BranchName("hidden/return/b-middle"), middle);

        new RetentionService(commits, refs).pruneAfterPublication(2, published);

        assertTrue(refs.read(new BranchName("hidden/return/a-old")).isEmpty());
        assertTrue(refs.read(new BranchName("hidden/return/b-middle")).isPresent());
        assertTrue(refs.read(published.name()).isPresent());
    }

    private static Commit commit(
            io.github.lumi.domain.model.ObjectId tree, CommitKind kind, long second) {
        return new Commit(
                tree, List.of(), new CommitAuthor(new UUID(0, 1), "Builder"),
                kind.name(), Instant.ofEpochSecond(second), new UUID(0, 2),
                Optional.empty(), kind, new CommitStatistics(0, 0, 0, 0));
    }
}
