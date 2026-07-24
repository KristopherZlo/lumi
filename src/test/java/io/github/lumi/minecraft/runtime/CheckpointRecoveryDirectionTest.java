package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.service.RecoveryChoice;
import io.github.lumi.minecraft.operation.WorkingIndexRecoveryPublication;
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

    private static WorkingIndexRecoveryPublication.TargetAction action(
            OperationKind kind,
            RecoveryChoice choice) {
        return FabricDimensionRuntime.checkpointRecoveryAction(kind, choice);
    }
}
