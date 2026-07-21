package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitKind;
import org.junit.jupiter.api.Test;

class ManualSaveGuardTest {
    @Test
    void onlyBuilderFacingSavesRequireTrackedChanges() {
        assertTrue(FabricDimensionRuntime.requiresBuilderChanges(CommitKind.MANUAL));
        assertTrue(FabricDimensionRuntime.requiresBuilderChanges(CommitKind.AMEND));
        assertTrue(FabricDimensionRuntime.requiresBuilderChanges(CommitKind.ZONE));
        assertFalse(FabricDimensionRuntime.requiresBuilderChanges(CommitKind.AUTO));
        assertFalse(FabricDimensionRuntime.requiresBuilderChanges(CommitKind.HIDDEN_RETURN));
    }
}
