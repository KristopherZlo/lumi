package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.ActiveBranch;
import io.github.lumi.domain.model.ActiveWorkspace;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.BranchSwitchPlan;
import io.github.lumi.domain.model.BranchSwitchTarget;
import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationTarget;
import io.github.lumi.domain.model.WorkspaceSwitchPlan;
import io.github.lumi.domain.model.WorkspaceSwitchTarget;
import io.github.lumi.domain.model.Zone;
import io.github.lumi.domain.model.ZoneRestoreTarget;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.minecraft.world.WorldStateApply;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.OperationJournalRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestoreOperationTest {
    @TempDir
    Path repositoryRoot;

    @Test
    void delegatesPublicationAfterExactVerification() throws IOException {
        CommitId current = id('b');
        CommitId target = id('c');
        var expectedRef = new io.github.lumi.domain.model.BranchRef(
                new BranchName("main"), current, 3);
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        AtomicInteger publications = new AtomicInteger();
        RestorePublication publication = restore -> {
            assertEquals(target, restore.targetCommit());
            publications.incrementAndGet();
        };
        RestoreOperation operation = RestoreOperation.start(
                new PreparedRestore(expectedRef, target, Map.of(), Map.of(), Map.of(), Map.of()),
                new RepairThenVerify(), publication, journals, UUID.randomUUID());

        operation.tick(Long.MAX_VALUE);
        operation.tick(Long.MAX_VALUE);
        operation.tick(Long.MAX_VALUE);
        operation.tick(Long.MAX_VALUE);

        assertEquals(RestoreStatus.COMPLETE, operation.status());
        assertEquals(1, publications.get());
    }

    @Test
    void createsBranchSwitchJournalFromExplicitSpec() throws IOException {
        BranchRef source = new BranchRef(new BranchName("main"), id('d'), 2);
        BranchRef target = new BranchRef(new BranchName("redstone-test"), id('e'), 4);
        var plan = new BranchSwitchPlan(new ActiveBranch(source.name(), 6), source, target);
        var restore = new PreparedRestore(
                source, target.commit(), Map.of(), Map.of(), Map.of(), Map.of());
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);

        RestoreOperation.startBranchSwitch(
                restore, new RepairThenVerify(), ignored -> { }, journals, UUID.randomUUID(),
                RestoreStateListener.NONE, plan);

        var journal = journals.read().orElseThrow();
        assertEquals(io.github.lumi.domain.model.OperationKind.BRANCH_SWITCH, journal.kind());
        assertEquals(Optional.of(new BranchSwitchTarget(target.name(), 4, 6)),
                journal.target().branchSwitch());
    }

    @Test
    void appendsWorkspacePointerTargetToBranchSwitchJournal() throws IOException {
        BranchRef source = new BranchRef(new BranchName("main"), id('a'), 2);
        BranchRef target = new BranchRef(new BranchName("workspace/next/main"), id('b'), 4);
        var branch = new BranchSwitchPlan(new ActiveBranch(source.name(), 6), source, target);
        var plan = new WorkspaceSwitchPlan(new ActiveWorkspace(new UUID(0, 1), 8),
                new UUID(0, 2), branch);
        var restore = new PreparedRestore(
                source, target.commit(), Map.of(), Map.of(), Map.of(), Map.of());
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);

        RestoreOperation.startWorkspaceSwitch(
                restore, new RepairThenVerify(), ignored -> { }, journals, UUID.randomUUID(),
                RestoreStateListener.NONE, plan);

        assertEquals(Optional.of(new WorkspaceSwitchTarget(
                        new UUID(0, 1), new UUID(0, 2), 8)),
                journals.read().orElseThrow().target().workspaceSwitch());
    }

    @Test
    void createsRefNeutralZoneRestoreJournal() throws IOException {
        BranchRef current = new BranchRef(new BranchName("main"), id('1'), 2);
        CommitId target = id('2');
        CommitId checkpoint = id('3');
        UUID workspace = new UUID(0, 4);
        Zone zone = new Zone(new UUID(0, 5), workspace, "Cell", 0,
                java.util.Set.of(), java.util.Set.of(), 7);
        var restore = new PreparedRestore(
                current, target, Map.of(), Map.of(), Map.of(), Map.of());
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);

        RestoreOperation.startZone(
                restore, new RepairThenVerify(), ignored -> { }, journals, UUID.randomUUID(),
                RestoreStateListener.NONE, zone, checkpoint);

        var journal = journals.read().orElseThrow();
        assertEquals(Optional.of(new ZoneRestoreTarget(workspace, zone.id(), 7)),
                journal.target().zoneRestore());
        assertEquals(Optional.of(checkpoint), journal.target().returnPoint());
    }

    @Test
    void createsMergeJournalBeforeApply() throws IOException {
        BranchRef current = new BranchRef(new BranchName("main"), id('4'), 3);
        CommitId merge = id('5');
        var restore = new PreparedRestore(
                current, merge, Map.of(), Map.of(), Map.of(), Map.of());
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);

        RestoreOperation.startMerge(
                restore, new RepairThenVerify(), ignored -> { }, journals,
                UUID.randomUUID(), RestoreStateListener.NONE);

        var journal = journals.read().orElseThrow();
        assertEquals(io.github.lumi.domain.model.OperationKind.MERGE, journal.kind());
        assertEquals(Optional.of(merge), journal.target().target());
        assertEquals(Optional.of(current.commit()), journal.target().returnPoint());
        assertEquals(OperationPhase.PREPARED, journal.phase());
    }

    @Test
    void createsPartialRestoreJournalWithoutChangingItsTarget() throws IOException {
        BranchRef source = new BranchRef(new BranchName("main"), id('f'), 2);
        var restore = new PreparedRestore(
                source, id('0'), Map.of(), Map.of(), Map.of(), Map.of());
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        var area = new BlockAreaTarget(new BlockBox(1, 2, 3, 4, 5, 6), false);
        CommitId checkpoint = id('a');

        RestoreOperation.startPartial(
                restore, new RepairThenVerify(), ignored -> { }, journals,
                UUID.randomUUID(), RestoreStateListener.NONE, area, checkpoint);

        assertEquals(Optional.of(area), journals.read().orElseThrow().target().blockArea());
        assertEquals(Optional.of(checkpoint), journals.read().orElseThrow().target().returnPoint());
    }

    @Test
    void createsQuickRollbackJournalToActiveHead() throws IOException {
        BranchRef source = new BranchRef(new BranchName("main"), id('1'), 2);
        CommitId checkpoint = id('2');
        var restore = new PreparedRestore(
                source, source.commit(), Map.of(), Map.of(), Map.of(), Map.of());
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);

        RestoreOperation.startQuickRollback(
                restore, new RepairThenVerify(), ignored -> { }, journals,
                UUID.randomUUID(), RestoreStateListener.NONE, checkpoint);

        var journal = journals.read().orElseThrow();
        assertEquals(io.github.lumi.domain.model.OperationKind.QUICK_ROLLBACK, journal.kind());
        assertEquals(Optional.of(source.commit()), journal.target().target());
        assertEquals(Optional.of(checkpoint), journal.target().returnPoint());
    }

    @Test
    void publishesRefOnlyAfterIncrementalApplyAndVerification() throws IOException {
        CommitId current = id('1');
        CommitId target = id('2');
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var expectedRef = refs.create(new BranchName("main"), current);
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        TwoStepApply world = new TwoStepApply();
        RecordingRestoreStateListener listener = new RecordingRestoreStateListener();

        RestoreOperation operation = RestoreOperation.start(
                new PreparedRestore(expectedRef, target, Map.of(), Map.of(), Map.of(), Map.of()),
                world, refs, journals,
                UUID.fromString("10000000-0000-0000-0000-000000000001"), listener);

        assertEquals(2, world.prepareCalls);
        assertEquals(OperationPhase.PREPARED, journals.read().orElseThrow().phase());
        assertEquals(current, refs.read(expectedRef.name()).orElseThrow().commit());

        assertEquals(RestoreStatus.APPLYING, operation.tick(Long.MAX_VALUE));
        assertEquals(current, refs.read(expectedRef.name()).orElseThrow().commit());
        assertEquals(RestoreStatus.VERIFYING, operation.tick(Long.MAX_VALUE));
        assertEquals(current, refs.read(expectedRef.name()).orElseThrow().commit());

        assertEquals(RestoreStatus.COMPLETE, operation.tick(Long.MAX_VALUE));
        assertEquals(target, refs.read(expectedRef.name()).orElseThrow().commit());
        assertTrue(journals.read().isEmpty());
        assertEquals(2, world.session.applyCalls);
        assertEquals(1, world.session.verifyCalls);
        assertEquals(1, listener.restored);
        assertEquals(0, listener.returned);
    }

    @Test
    void repairsOneMismatchBeforePublishing() throws IOException {
        CommitId current = id('3');
        CommitId target = id('4');
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var expectedRef = refs.create(new BranchName("main"), current);
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        RepairThenVerify world = new RepairThenVerify();
        RestoreOperation operation = RestoreOperation.start(
                new PreparedRestore(expectedRef, target, Map.of(), Map.of(), Map.of(), Map.of()),
                world, refs, journals, UUID.randomUUID());

        assertEquals(RestoreStatus.VERIFYING, operation.tick(Long.MAX_VALUE));
        assertEquals(RestoreStatus.REPAIRING, operation.tick(Long.MAX_VALUE));
        assertEquals(RestoreStatus.VERIFYING, operation.tick(Long.MAX_VALUE));
        assertEquals(RestoreStatus.COMPLETE, operation.tick(Long.MAX_VALUE));
        assertEquals(1, world.session.repairCalls);
        assertEquals(target, refs.read(expectedRef.name()).orElseThrow().commit());
    }

    @Test
    void returnsToPreparedPreOperationStateWhenTargetCannotVerify() throws IOException {
        CommitId current = id('5');
        CommitId target = id('6');
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var expectedRef = refs.create(new BranchName("main"), current);
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        ReturnAfterMismatch world = new ReturnAfterMismatch(true);
        RecordingRestoreStateListener listener = new RecordingRestoreStateListener();
        RestoreOperation operation = RestoreOperation.start(
                new PreparedRestore(expectedRef, target, Map.of(), Map.of(), Map.of(), Map.of()),
                world, refs, journals, UUID.randomUUID(), listener);

        assertEquals(RestoreStatus.VERIFYING, operation.tick(Long.MAX_VALUE));
        assertEquals(RestoreStatus.REPAIRING, operation.tick(Long.MAX_VALUE));
        assertEquals(RestoreStatus.VERIFYING, operation.tick(Long.MAX_VALUE));
        assertEquals(RestoreStatus.RETURNING, operation.tick(Long.MAX_VALUE));
        assertEquals(RestoreStatus.RETURNING, operation.tick(Long.MAX_VALUE));
        assertEquals(RestoreStatus.RETURNED, operation.tick(Long.MAX_VALUE));
        assertEquals(MutationTerminalState.RETURNED, operation.terminalState());
        assertEquals(2, world.beginCalls);
        assertEquals(0, listener.restored);
        assertEquals(1, listener.returned);
        assertEquals(current, refs.read(expectedRef.name()).orElseThrow().commit());
        assertTrue(journals.read().isEmpty());
    }

    @Test
    void keepsJournalAndDegradesWhenNeitherDirectionVerifies() throws IOException {
        CommitId current = id('7');
        CommitId target = id('8');
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var expectedRef = refs.create(new BranchName("main"), current);
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        RestoreOperation operation = RestoreOperation.start(
                new PreparedRestore(expectedRef, target, Map.of(), Map.of(), Map.of(), Map.of()),
                new ReturnAfterMismatch(false), refs, journals, UUID.randomUUID());

        assertEquals(RestoreStatus.VERIFYING, operation.tick(Long.MAX_VALUE));
        assertEquals(RestoreStatus.REPAIRING, operation.tick(Long.MAX_VALUE));
        assertEquals(RestoreStatus.VERIFYING, operation.tick(Long.MAX_VALUE));
        assertEquals(RestoreStatus.RETURNING, operation.tick(Long.MAX_VALUE));
        assertEquals(RestoreStatus.RETURNING, operation.tick(Long.MAX_VALUE));
        assertEquals(RestoreStatus.RETURNING, operation.tick(Long.MAX_VALUE));
        assertEquals(RestoreStatus.RETURNING, operation.tick(Long.MAX_VALUE));
        assertEquals(RestoreStatus.DEGRADED, operation.tick(Long.MAX_VALUE));
        assertEquals(MutationTerminalState.DEGRADED, operation.terminalState());
        assertEquals(OperationPhase.DEGRADED, journals.read().orElseThrow().phase());
        assertEquals(current, refs.read(expectedRef.name()).orElseThrow().commit());
    }

    @Test
    void cancelsPreparedJournalBeforeAnyWorldMutation() throws IOException {
        CommitId current = id('9');
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var expectedRef = refs.create(new BranchName("main"), current);
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        RestoreOperation operation = RestoreOperation.start(
                new PreparedRestore(expectedRef, id('a'), Map.of(), Map.of(), Map.of(), Map.of()),
                new RepairThenVerify(), refs, journals, UUID.randomUUID());

        operation.cancelBeforeApply();

        assertTrue(journals.read().isEmpty());
        assertEquals(MutationTerminalState.CANCELLED, operation.terminalState());
        assertEquals(current, refs.read(expectedRef.name()).orElseThrow().commit());
    }

    @Test
    void resumesAnExistingApplyJournalWithoutReplacingIt() throws IOException {
        CommitId current = id('b');
        CommitId target = id('c');
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var expected = refs.create(new BranchName("main"), current);
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        OperationJournal journal = journals.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.RESTORE, OperationPhase.APPLYING,
                new OperationTarget(expected.name(), expected.commit(), expected.revision(),
                        Optional.of(target), Optional.of(current))));

        RestoreOperation operation = RestoreOperation.resume(
                new PreparedRestore(expected, target, Map.of(), Map.of(), Map.of(), Map.of()),
                new ImmediatelyVerified(), new BranchRefRestorePublication(refs),
                journals, journal, RestoreStateListener.NONE);
        operation.tick(Long.MAX_VALUE);
        operation.tick(Long.MAX_VALUE);

        assertEquals(RestoreStatus.COMPLETE, operation.status());
        assertEquals(target, refs.read(expected.name()).orElseThrow().commit());
        assertTrue(journals.read().isEmpty());
    }

    @Test
    void retainsJournalUntilPublicationBecomesDurable() throws IOException {
        var expected = new BranchRef(new BranchName("main"), id('d'), 1);
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        DelayedPublication publication = new DelayedPublication();
        RestoreOperation operation = RestoreOperation.start(
                new PreparedRestore(expected, id('e'), Map.of(), Map.of(), Map.of(), Map.of()),
                new ImmediatelyVerified(), publication, journals, UUID.randomUUID());

        operation.tick(Long.MAX_VALUE);
        operation.tick(Long.MAX_VALUE);

        assertEquals(RestoreStatus.PUBLISHING, operation.status());
        assertEquals(OperationPhase.VERIFYING, journals.read().orElseThrow().phase());
        publication.durable = true;
        operation.tick(Long.MAX_VALUE);
        assertEquals(RestoreStatus.COMPLETE, operation.status());
        assertTrue(journals.read().isEmpty());
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }

    private static final class RecordingRestoreStateListener implements RestoreStateListener {
        private int restored;
        private int returned;
        @Override public void restored(PreparedRestore restore) { restored++; }
        @Override public void returned(PreparedRestore restore) { returned++; }
    }

    private static final class ImmediatelyVerified implements TestWorldApply {
        @Override public ApplySession begin(PreparedState target) {
            return new ApplySession() {
                @Override public boolean applyUntil(long deadlineNanos) { return true; }
                @Override public Verification verifyUntil(long deadlineNanos) {
                    return Verification.VERIFIED;
                }
                @Override public boolean repairUntil(long deadlineNanos) { return true; }
                @Override public void restartVerification() { }
            };
        }
    }

    private static final class DelayedPublication implements RestorePublication {
        private boolean published;
        private boolean durable;
        @Override public void publish(PreparedRestore restore) { published = true; }
        @Override public boolean isDurable() { return published && durable; }
    }

    private static final class TwoStepApply implements TestWorldApply {
        private final Session session = new Session();
        private int prepareCalls;

        @Override public PreparedState prepare(State target) {
            prepareCalls++;
            return new TestPrepared(target);
        }

        @Override
        public ApplySession begin(PreparedState target) {
            return session;
        }

        private static final class Session implements ApplySession {
            private int applyCalls;
            private int verifyCalls;

            @Override
            public boolean applyUntil(long deadlineNanos) {
                return ++applyCalls == 2;
            }

            @Override
            public Verification verifyUntil(long deadlineNanos) {
                verifyCalls++;
                return Verification.VERIFIED;
            }

            @Override
            public boolean repairUntil(long deadlineNanos) {
                throw new AssertionError("Repair was not expected");
            }

            @Override
            public void restartVerification() {
                throw new AssertionError("Repair was not expected");
            }
        }
    }

    private static final class RepairThenVerify implements TestWorldApply {
        private final Session session = new Session();

        @Override
        public ApplySession begin(PreparedState target) {
            return session;
        }

        private static final class Session implements ApplySession {
            private int verifyCalls;
            private int repairCalls;

            @Override public boolean applyUntil(long deadlineNanos) { return true; }
            @Override public Verification verifyUntil(long deadlineNanos) {
                return ++verifyCalls == 1 ? Verification.MISMATCH : Verification.VERIFIED;
            }
            @Override public boolean repairUntil(long deadlineNanos) {
                repairCalls++;
                return true;
            }
            @Override public void restartVerification() { }
        }
    }

    private static final class ReturnAfterMismatch implements TestWorldApply {
        private final boolean returnVerifies;
        private int beginCalls;

        private ReturnAfterMismatch(boolean returnVerifies) {
            this.returnVerifies = returnVerifies;
        }

        @Override
        public ApplySession begin(PreparedState target) {
            beginCalls++;
            return beginCalls == 1 ? new AlwaysMismatch() : new ReturnSession(returnVerifies);
        }

        private static final class AlwaysMismatch implements ApplySession {
            @Override public boolean applyUntil(long deadlineNanos) { return true; }
            @Override public Verification verifyUntil(long deadlineNanos) { return Verification.MISMATCH; }
            @Override public boolean repairUntil(long deadlineNanos) { return true; }
            @Override public void restartVerification() { }
        }

        private record ReturnSession(boolean verifies) implements ApplySession {
            @Override public boolean applyUntil(long deadlineNanos) { return true; }
            @Override public Verification verifyUntil(long deadlineNanos) {
                return verifies ? Verification.VERIFIED : Verification.MISMATCH;
            }
            @Override public boolean repairUntil(long deadlineNanos) { return true; }
            @Override public void restartVerification() { }
        }
    }

    private interface TestWorldApply extends WorldStateApply {
        @Override default PreparedState prepare(State target) {
            return new TestPrepared(target);
        }
    }

    private record TestPrepared(WorldStateApply.State state)
            implements WorldStateApply.PreparedState { }
}
