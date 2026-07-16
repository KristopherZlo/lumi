package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
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
    @TempDir Path repositoryRoot;

    @Test
    void keepsLatestSixteenHiddenCheckpointsAndEveryVisibleRef() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var tree = new WorldObjectRepository(repositoryRoot)
                .write(new DimensionTree(Map.of()));
        CommitId visible = commits.write(commit(tree, CommitKind.MANUAL, 0));
        refs.create(new BranchName("main"), visible);
        for (int index = 0; index < 20; index++) {
            CommitId hidden = commits.write(commit(tree, CommitKind.HIDDEN_RETURN, index + 1));
            refs.create(new BranchName("hidden/return/" + index), hidden);
        }

        int deleted = new RetentionService(commits, refs).pruneHiddenRefs(16);

        assertEquals(4, deleted);
        assertTrue(refs.read(new BranchName("main")).isPresent());
        assertEquals(16, refs.list().stream()
                .filter(ref -> ref.name().value().startsWith("hidden/")).count());
        assertTrue(refs.read(new BranchName("hidden/return/19")).isPresent());
        assertEquals(false, refs.read(new BranchName("hidden/return/0")).isPresent());
    }

    private static Commit commit(
            io.github.lumi.domain.model.ObjectId tree, CommitKind kind, long second) {
        return new Commit(
                tree, List.of(), new CommitAuthor(new UUID(0, 1), "Builder"),
                kind.name(), Instant.ofEpochSecond(second), new UUID(0, 2),
                Optional.empty(), kind, new CommitStatistics(0, 0, 0, 0));
    }
}
