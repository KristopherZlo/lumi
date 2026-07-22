package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import org.junit.jupiter.api.Test;

class FabricDimensionRuntimeTest {
    private static final CommitId HEAD = new CommitId(new ObjectId("a".repeat(64)));
    private static final CommitId OTHER = new CommitId(new ObjectId("b".repeat(64)));

    @Test
    void restoreIsNoOpOnlyForCleanCurrentHead() {
        assertTrue(FabricDimensionRuntime.isRestoreNoOp(HEAD, HEAD, false));
        assertFalse(FabricDimensionRuntime.isRestoreNoOp(HEAD, HEAD, true));
        assertFalse(FabricDimensionRuntime.isRestoreNoOp(HEAD, OTHER, false));
    }
}
