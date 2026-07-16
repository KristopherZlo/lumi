package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.service.DimensionHistoryInitializer;
import io.github.lumi.domain.service.ZoneService;
import io.github.lumi.storage.repository.ActiveBranchRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import io.github.lumi.storage.repository.ZoneRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DimensionComparisonQueriesTest {
    @TempDir Path repository;

    @Test
    void rejectsACommitOutsideTheSelectedWorkspaceBeforeDecoding()
            throws Exception {
        UUID selectedWorkspace = new UUID(0, 1);
        CommitRepository commits = new CommitRepository(repository);
        var main = new DimensionHistoryInitializer(
                new WorldObjectRepository(repository), commits,
                new BranchRefRepository(repository),
                new ActiveBranchRepository(repository))
                .initialize(selectedWorkspace);
        Commit root = commits.read(main.commit());
        var foreign = commits.write(new Commit(
                root.tree(), List.of(), new CommitAuthor(new UUID(0, 2), "Other"),
                "Foreign", Instant.EPOCH, new UUID(0, 3), Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(0, 0, 0, 0)));
        var queries = new DimensionComparisonQueries(
                repository, Runnable::run,
                new ZoneService(new ZoneRepository(repository)),
                () -> selectedWorkspace);

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> queries.compare(main.commit(), foreign, () -> false).join());

        assertInstanceOf(IOException.class, failure.getCause());
    }
}
