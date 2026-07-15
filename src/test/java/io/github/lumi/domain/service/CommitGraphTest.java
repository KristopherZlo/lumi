package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.storage.repository.CommitRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommitGraphTest {
    private static final ObjectId TREE = ObjectId.hash(new byte[] {1});
    @TempDir java.nio.file.Path repositoryRoot;

    @Test
    void choosesNearestAncestorAcrossBothMergeParents() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        CommitId root = write(commits, "root", List.of());
        CommitId left = write(commits, "left", List.of(root));
        CommitId right = write(commits, "right", List.of(root));
        CommitId current = write(commits, "merge", List.of(left, right));
        CommitId source = write(commits, "source", List.of(right));

        assertEquals(right, new CommitGraph(commits).nearestCommonAncestor(current, source));
    }

    @Test
    void rejectsUnrelatedHistories() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        CommitId first = write(commits, "first", List.of());
        CommitId second = write(commits, "second", List.of());

        assertThrows(IOException.class,
                () -> new CommitGraph(commits).nearestCommonAncestor(first, second));
    }

    private static CommitId write(
            CommitRepository commits, String message, List<CommitId> parents) throws Exception {
        return commits.write(new Commit(
                TREE, parents, new CommitAuthor(new UUID(0, 1), "Builder"),
                message, Instant.EPOCH, new UUID(0, 2), Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(0, 0, 0, 0)));
    }
}
