package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.model.OperationTarget;
import io.github.lumi.storage.repository.ActiveBranchRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.OperationJournalRepository;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishedApplyRecoveryTest {
    @TempDir Path repositoryRoot;

    @Test
    void clearsJournalWhenRefProvesVerifiedPublicationCompleted() throws Exception {
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var expected = refs.create(new BranchName("main"), id('1'));
        CommitId target = id('2');
        refs.compareAndSet(expected, target);
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        OperationJournal journal = journals.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.RESTORE, OperationPhase.VERIFYING,
                new OperationTarget(expected.name(), expected.commit(), expected.revision(),
                        Optional.of(target), Optional.of(expected.commit()))));

        boolean completed = new PublishedApplyRecovery(
                refs, new ActiveBranchRepository(repositoryRoot), journals)
                .finalizeIfPublished(journal);

        assertTrue(completed);
        assertTrue(journals.read().isEmpty());
    }

    @Test
    void leavesUnpublishedJournalForUserRecoveryChoice() throws Exception {
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var expected = refs.create(new BranchName("main"), id('3'));
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        OperationJournal journal = journals.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.RESTORE, OperationPhase.APPLYING,
                new OperationTarget(expected.name(), expected.commit(), expected.revision(),
                        Optional.of(id('4')), Optional.of(expected.commit()))));

        boolean completed = new PublishedApplyRecovery(
                refs, new ActiveBranchRepository(repositoryRoot), journals)
                .finalizeIfPublished(journal);

        assertFalse(completed);
        assertTrue(journals.read().isPresent());
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
