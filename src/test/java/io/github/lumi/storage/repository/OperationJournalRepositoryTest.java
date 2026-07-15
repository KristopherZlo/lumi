package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.model.OperationTarget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationJournalRepositoryTest {
    @TempDir
    Path repositoryRoot;

    @Test
    void persistsImmutableTargetsWhilePhaseAdvances() throws IOException {
        OperationJournalRepository repository = new OperationJournalRepository(repositoryRoot);
        OperationJournal created = repository.create(new OperationJournal(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                OperationKind.RESTORE,
                OperationPhase.PREPARED,
                target()));

        OperationJournal applying = repository.advance(created, OperationPhase.APPLYING);

        assertEquals(created.target(), applying.target());
        assertEquals(applying, new OperationJournalRepository(repositoryRoot).read().orElseThrow());
    }

    @Test
    void stalePhaseCannotAdvanceOrClearJournal() throws IOException {
        OperationJournalRepository repository = new OperationJournalRepository(repositoryRoot);
        OperationJournal prepared = repository.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.SAVE, OperationPhase.PREPARED, target()));
        OperationJournal writing = repository.advance(prepared, OperationPhase.WRITING_OBJECTS);

        assertThrows(JournalConflictException.class,
                () -> repository.advance(prepared, OperationPhase.COMMIT_WRITTEN));
        assertThrows(JournalConflictException.class, () -> repository.clear(prepared));
        repository.clear(writing);
        assertEquals(Optional.empty(), repository.read());
    }

    private static OperationTarget target() {
        return new OperationTarget(
                new BranchName("main"), id("expected"), 7,
                Optional.of(id("target")), Optional.of(id("return")));
    }

    private static CommitId id(String value) {
        return CommitId.hash(value.getBytes(StandardCharsets.UTF_8));
    }
}
