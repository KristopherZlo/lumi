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
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        List<HistoryEntry> history = new HistoryQueryService(commits, refs)
                .firstParent(new BranchName("main"), 2);

        assertEquals(List.of(third, second), history.stream().map(HistoryEntry::id).toList());
        assertEquals(List.of("Third", "Second"),
                history.stream().map(entry -> entry.commit().message()).toList());
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
}
