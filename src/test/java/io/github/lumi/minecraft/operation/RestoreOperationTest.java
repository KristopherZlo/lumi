package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.minecraft.world.WorldStateApply;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.OperationJournalRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestoreOperationTest {
    @TempDir
    Path repositoryRoot;

    @Test
    void publishesRefOnlyAfterIncrementalApplyAndVerification() throws IOException {
        CommitId current = id('1');
        CommitId target = id('2');
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        var expectedRef = refs.create(new BranchName("main"), current);
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        TwoStepApply world = new TwoStepApply();

        RestoreOperation operation = RestoreOperation.start(
                new PreparedRestore(expectedRef, target, Map.of(), Map.of(), Map.of(), Map.of()),
                world, refs, journals,
                UUID.fromString("10000000-0000-0000-0000-000000000001"));

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
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }

    private static final class TwoStepApply implements WorldStateApply {
        private final Session session = new Session();

        @Override
        public ApplySession begin(State target) {
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
}
