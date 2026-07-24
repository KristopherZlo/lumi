package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchSwitchTarget;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.model.OperationTarget;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.model.WorkspaceSwitchTarget;
import io.github.lumi.storage.repository.ActiveBranchRepository;
import io.github.lumi.storage.repository.ActiveWorkspaceRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishedApplyRecoveryTest {
    @TempDir Path repositoryRoot;

    @Test
    void clearsPersistedJournalWhenRefProvesPublicationCompleted() throws Exception {
        assertPublishedJournalCleared(
                repositoryRoot.resolve("legacy"), OperationPhase.VERIFYING);
        assertPublishedJournalCleared(
                repositoryRoot.resolve("durable"), OperationPhase.WORLD_PERSISTED);
    }

    private static void assertPublishedJournalCleared(
            Path root, OperationPhase phase) throws Exception {
        BranchRefRepository refs = new BranchRefRepository(root);
        var expected = refs.create(new BranchName("main"), id('1'));
        CommitId target = id('2');
        refs.compareAndSet(expected, target);
        OperationJournalRepository journals = new OperationJournalRepository(root);
        OperationJournal journal = journals.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.RESTORE, phase,
                new OperationTarget(expected.name(), expected.commit(), expected.revision(),
                        Optional.of(target), Optional.of(expected.commit()))));

        boolean completed = new PublishedApplyRecovery(
                refs, new ActiveBranchRepository(root), journals)
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

    @Test
    void checkpointActionsRemainRefNeutralRecoveryWork() throws Exception {
        for (OperationKind kind : java.util.List.of(
                OperationKind.QUICK_ROLLBACK, OperationKind.CHECKPOINT_UNDO)) {
            Path root = repositoryRoot.resolve(kind.name());
            BranchRefRepository refs = new BranchRefRepository(root);
            var expected = refs.create(new BranchName("main"), id('3'));
            OperationJournalRepository journals = new OperationJournalRepository(root);
            OperationJournal journal = journals.create(new OperationJournal(
                    UUID.randomUUID(), kind, OperationPhase.WORLD_PERSISTED,
                    new OperationTarget(
                            expected.name(), expected.commit(), expected.revision(),
                            Optional.of(id('4')), Optional.of(id('5')))));

            assertFalse(new PublishedApplyRecovery(
                    refs, new ActiveBranchRepository(root), journals)
                    .finalizeIfPublished(journal));
            assertEquals(journal, journals.read().orElseThrow());
            assertEquals(expected, refs.read(expected.name()).orElseThrow());
        }
    }

    @Test
    void clearsWorkspaceJournalOnlyAfterBothPointersPublish() throws Exception {
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var source = refs.create(new BranchName("main"), id('5'));
        var target = refs.create(new BranchName("workspace/next/main"), id('6'));
        var activeBranch = new ActiveBranchRepository(repositoryRoot);
        var expectedBranch = activeBranch.create(source.name());
        activeBranch.compareAndSet(expectedBranch, target.name());
        var activeWorkspace = new ActiveWorkspaceRepository(repositoryRoot);
        UUID sourceWorkspace = new UUID(0, 1);
        UUID targetWorkspace = new UUID(0, 2);
        var expectedWorkspace = activeWorkspace.create(sourceWorkspace);
        OperationTarget operationTarget = new OperationTarget(
                source.name(), source.commit(), source.revision(), Optional.of(target.commit()),
                Optional.of(source.commit()), Optional.of(new BranchSwitchTarget(
                        target.name(), target.revision(), expectedBranch.revision())),
                Optional.empty(), false, Optional.of(new WorkspaceSwitchTarget(
                        sourceWorkspace, targetWorkspace, expectedWorkspace.revision())));
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        OperationJournal journal = journals.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.BRANCH_SWITCH,
                OperationPhase.REF_PUBLISHED, operationTarget));

        assertFalse(new PublishedApplyRecovery(
                refs, activeBranch, activeWorkspace, journals).finalizeIfPublished(journal));
        activeWorkspace.compareAndSet(expectedWorkspace, targetWorkspace);
        assertTrue(new PublishedApplyRecovery(
                refs, activeBranch, activeWorkspace, journals).finalizeIfPublished(journal));
        assertTrue(journals.read().isEmpty());
    }

    @Test
    void clearsCapturedAmbientWorkBeforeFinalizingPublishedBranchSwitch() throws Exception {
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var source = refs.create(new BranchName("main"), id('7'));
        var target = refs.create(new BranchName("variant"), id('8'));
        var active = new ActiveBranchRepository(repositoryRoot);
        var expectedActive = active.create(source.name());
        active.compareAndSet(expectedActive, target.name());
        OperationTarget operationTarget = new OperationTarget(
                source.name(), source.commit(), source.revision(), Optional.of(target.commit()),
                Optional.of(id('9')), Optional.of(new BranchSwitchTarget(
                        target.name(), target.revision(), expectedActive.revision())));
        var captured = new WorkingIndexSnapshot(Map.of(new SectionKey(1, 0, 1), 4L));
        var working = new WorkingIndexRepository(repositoryRoot);
        working.write(new WorkingIndexRepository.State(
                captured, WorkingIndexSnapshot.empty()));
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        OperationJournal journal = journals.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.BRANCH_SWITCH,
                OperationPhase.VERIFYING, operationTarget, Optional.of(captured)));

        assertTrue(new PublishedApplyRecovery(
                refs, active, null, working, journals)
                .finalizeIfPublished(journal));
        assertTrue(journals.read().isEmpty());
        assertTrue(working.read().generations().isEmpty());
    }

    @Test
    void clearsOnlyExactCapturedGenerationsAfterPublishedRestore() throws Exception {
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var source = refs.create(new BranchName("main"), id('a'));
        CommitId target = id('b');
        refs.compareAndSet(source, target);
        SectionKey cleared = new SectionKey(2, 0, 2);
        SectionKey newer = new SectionKey(3, 0, 3);
        SectionKey ambient = new SectionKey(4, 0, 4);
        var captured = new WorkingIndexSnapshot(Map.of(cleared, 4L, newer, 4L));
        var working = new WorkingIndexRepository(repositoryRoot);
        working.write(new WorkingIndexRepository.State(
                new WorkingIndexSnapshot(Map.of(
                        cleared, 4L, newer, 5L, ambient, 2L)),
                new WorkingIndexSnapshot(Map.of(cleared, 4L, newer, 5L))));
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        OperationJournal journal = journals.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.RESTORE,
                OperationPhase.WORLD_PERSISTED,
                new OperationTarget(
                        source.name(), source.commit(), source.revision(),
                        Optional.of(target), Optional.of(id('c'))),
                Optional.of(captured)));

        assertTrue(new PublishedApplyRecovery(
                refs, new ActiveBranchRepository(repositoryRoot), null,
                working, journals).finalizeIfPublished(journal));

        WorkingIndexRepository.State persisted = working.readState();
        assertEquals(Map.of(newer, 5L, ambient, 2L),
                persisted.working().generations());
        assertEquals(Map.of(newer, 5L), persisted.builder().generations());
        assertTrue(journals.read().isEmpty());
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
