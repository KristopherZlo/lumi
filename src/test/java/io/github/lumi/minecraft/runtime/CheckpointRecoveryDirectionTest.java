package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.model.OperationTarget;
import io.github.lumi.domain.service.RecoveryChoice;
import io.github.lumi.minecraft.operation.WorkingIndexRecoveryPublication;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckpointRecoveryDirectionTest {
    @Test
    void initialQuickRestoreClearsOnResumeAndRestoresOnReturn() {
        assertEquals(WorkingIndexRecoveryPublication.TargetAction.CLEAR,
                action(OperationKind.QUICK_ROLLBACK, RecoveryChoice.RESUME_TARGET));
        assertEquals(WorkingIndexRecoveryPublication.TargetAction.RESTORE,
                action(OperationKind.QUICK_ROLLBACK, RecoveryChoice.RETURN_CHECKPOINT));
    }

    @Test
    void checkpointUndoRestoresOnResumeAndClearsOnReturn() {
        assertEquals(WorkingIndexRecoveryPublication.TargetAction.RESTORE,
                action(OperationKind.CHECKPOINT_UNDO, RecoveryChoice.RESUME_TARGET));
        assertEquals(WorkingIndexRecoveryPublication.TargetAction.CLEAR,
                action(OperationKind.CHECKPOINT_UNDO, RecoveryChoice.RETURN_CHECKPOINT));
    }

    @Test
    void rejectsAnUnrelatedOperationKind() {
        assertThrows(IllegalArgumentException.class, () ->
                action(OperationKind.RESTORE, RecoveryChoice.RESUME_TARGET));
    }

    @Test
    void startupRetainsOnlyCommitsNamedByCheckpointRecovery() {
        CommitId target = id('2');
        CommitId returnPoint = id('3');
        OperationTarget pair = new OperationTarget(
                new BranchName("main"), id('1'), 0,
                Optional.of(target), Optional.of(returnPoint));

        assertEquals(Set.of(target, returnPoint),
                FabricDimensionRuntime.retainedSessionCheckpointCommits(
                        journal(OperationKind.CHECKPOINT_UNDO, pair)));
        assertEquals(Set.of(target, returnPoint),
                FabricDimensionRuntime.retainedSessionCheckpointCommits(
                        journal(OperationKind.QUICK_ROLLBACK, pair)));
        assertEquals(Set.of(),
                FabricDimensionRuntime.retainedSessionCheckpointCommits(
                        journal(OperationKind.RESTORE, pair)));
        assertEquals(Set.of(),
                FabricDimensionRuntime.retainedSessionCheckpointCommits(null));
    }

    private static WorkingIndexRecoveryPublication.TargetAction action(
            OperationKind kind,
            RecoveryChoice choice) {
        return FabricDimensionRuntime.checkpointRecoveryAction(kind, choice);
    }

    private static OperationJournal journal(
            OperationKind kind,
            OperationTarget target) {
        return new OperationJournal(
                UUID.randomUUID(), kind, OperationPhase.APPLYING, target);
    }

    private static CommitId id(char value) {
        return new CommitId(new ObjectId(String.valueOf(value).repeat(64)));
    }
}
