package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.model.OperationTarget;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveJournalRecoveryTest {
    @TempDir Path repositoryRoot;

    @Test
    void publishesValidatedCommitThenClearsInterruptedSaveJournal() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        var oldCommit = commits.write(commit("old"));
        var savedCommit = commits.write(commit("saved"));
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var expected = refs.create(new BranchName("main"), oldCommit);
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        OperationJournal journal = journals.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.SAVE, OperationPhase.COMMIT_WRITTEN,
                new OperationTarget(expected.name(), expected.commit(), expected.revision(),
                        Optional.of(savedCommit), Optional.empty())));

        new SaveJournalRecovery(commits, refs, journals).recover(journal);

        assertEquals(savedCommit, refs.read(expected.name()).orElseThrow().commit());
        assertTrue(journals.read().isEmpty());
    }

    @Test
    void acceptsRefPublishedBeforeItsJournalPhaseWasForced() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        var oldCommit = commits.write(commit("old"));
        var savedCommit = commits.write(commit("saved"));
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var expected = refs.create(new BranchName("main"), oldCommit);
        refs.compareAndSet(expected, savedCommit);
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        OperationJournal journal = journals.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.SAVE, OperationPhase.COMMIT_WRITTEN,
                new OperationTarget(expected.name(), expected.commit(), expected.revision(),
                        Optional.of(savedCommit), Optional.empty())));

        new SaveJournalRecovery(commits, refs, journals).recover(journal);

        assertEquals(savedCommit, refs.read(expected.name()).orElseThrow().commit());
        assertTrue(journals.read().isEmpty());
    }

    @Test
    void publishesThenClearsOnlyTheJournaledGenerationBoundary() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        var oldCommit = commits.write(commit("old"));
        var savedCommit = commits.write(commit("saved"));
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var expected = refs.create(new BranchName("main"), oldCommit);
        SectionKey captured = new SectionKey(1, 2, 3);
        SectionKey ambient = new SectionKey(4, 5, 6);
        WorkingIndexRepository working = new WorkingIndexRepository(repositoryRoot);
        working.write(new WorkingIndexRepository.State(
                new WorkingIndexSnapshot(Map.of(captured, 3L, ambient, 2L)),
                new WorkingIndexSnapshot(Map.of(captured, 3L))));
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        OperationJournal journal = journals.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.SAVE, OperationPhase.COMMIT_WRITTEN,
                new OperationTarget(expected.name(), expected.commit(), expected.revision(),
                        Optional.of(savedCommit), Optional.empty()),
                Optional.of(new WorkingIndexSnapshot(Map.of(captured, 3L)))));

        new SaveJournalRecovery(commits, refs, journals, working).recover(journal);

        assertEquals(savedCommit, refs.read(expected.name()).orElseThrow().commit());
        assertEquals(new WorkingIndexRepository.State(
                        new WorkingIndexSnapshot(Map.of(ambient, 2L)),
                        WorkingIndexSnapshot.empty()),
                working.readState());
        assertTrue(journals.read().isEmpty());
    }

    private static Commit commit(String message) {
        return new Commit(ObjectId.hash(message.getBytes()), List.of(),
                new CommitAuthor(new UUID(0, 1), "Builder"), message, Instant.EPOCH,
                new UUID(0, 2), Optional.empty(), CommitKind.MANUAL,
                new CommitStatistics(0, 0, 0, 0));
    }
}
