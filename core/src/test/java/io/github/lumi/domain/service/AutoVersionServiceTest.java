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

class AutoVersionServiceTest {
    @TempDir Path repositoryRoot;

    @Test
    void keepsNewestSixtyFourVersionsForOnlyTheRequestedBranch() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        AutoVersionService service = new AutoVersionService(commits, refs);
        BranchName main = new BranchName("main");
        BranchName idea = new BranchName("idea");
        UUID workspace = new UUID(0, 2);
        var tree = new WorldObjectRepository(repositoryRoot)
                .write(new DimensionTree(Map.of()));
        CommitId root = commits.write(commit(tree, workspace, 0, CommitKind.MANUAL));
        refs.create(main, root);
        for (int index = 1; index <= 66; index++) {
            CommitId auto = commits.write(commit(tree, workspace, index, CommitKind.AUTO));
            refs.create(service.refName(main, new UUID(0, index)), auto);
        }
        CommitId other = commits.write(commit(tree, workspace, 100, CommitKind.AUTO));
        refs.create(service.refName(idea, new UUID(1, 1)), other);

        assertEquals(2, service.prune(main, 64));

        var versions = service.list(main, workspace, 64);
        assertEquals(64, versions.size());
        assertEquals(Instant.ofEpochSecond(66), versions.getFirst().commit().timestamp());
        assertEquals(Instant.ofEpochSecond(3), versions.getLast().commit().timestamp());
        assertTrue(refs.read(service.refName(idea, new UUID(1, 1))).isPresent());
    }

    private static Commit commit(
            io.github.lumi.domain.model.ObjectId tree,
            UUID workspace,
            long second,
            CommitKind kind) {
        return new Commit(
                tree, List.of(), new CommitAuthor(new UUID(0, 1), "Lumi"),
                kind.name(), Instant.ofEpochSecond(second), workspace,
                Optional.empty(), kind, new CommitStatistics(0, 0, 0, 0));
    }
}
