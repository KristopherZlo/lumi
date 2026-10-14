package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommitRepositoryTest {
    @TempDir
    Path repositoryRoot;

    @Test
    void writesReopensAndDeduplicatesImmutableCommit() throws IOException {
        CommitRepository repository = new CommitRepository(repositoryRoot);
        Commit commit = new Commit(
                ObjectId.hash(new byte[] {1}),
                List.of(),
                new CommitAuthor(UUID.fromString("10000000-0000-0000-0000-000000000001"), "Builder"),
                "First",
                Instant.EPOCH,
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                Optional.empty(),
                CommitKind.MANUAL,
                new CommitStatistics(1, 0, 1, 0));

        var first = repository.write(commit);
        var second = repository.write(commit);

        assertEquals(first, second);
        assertEquals(commit, repository.read(first));
        try (var files = Files.walk(repositoryRoot.resolve("commits"))) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }
    }
}
