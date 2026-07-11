package io.github.luma.minecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldApplyOperationProfileTest {

    @Test
    void historyApplyOperationsUseFastButBoundedProfile() {
        assertEquals(WorldOperationKind.HISTORY_APPLY, WorldOperationKind.fromLabel("restore-version"));
        assertEquals(WorldOperationKind.HISTORY_APPLY, WorldOperationKind.fromLabel("partial-restore"));
        assertEquals(WorldOperationKind.HISTORY_APPLY, WorldOperationKind.fromLabel("zone-restore"));
        assertEquals(WorldOperationKind.HISTORY_APPLY, WorldOperationKind.fromLabel("recovery"));
        assertEquals(WorldOperationKind.HISTORY_APPLY, WorldOperationKind.fromLabel("restore-draft"));
        assertEquals(WorldOperationKind.HISTORY_APPLY, WorldOperationKind.fromLabel("quick-rollback"));
        assertEquals(WorldOperationKind.HISTORY_APPLY, WorldOperationKind.fromLabel("undo-action"));
        assertEquals(WorldOperationKind.HISTORY_APPLY, WorldOperationKind.fromLabel("redo-action"));
        assertEquals(WorldOperationKind.HISTORY_APPLY, WorldOperationKind.fromLabel("merge-variant"));
        assertEquals(WorldApplyProfile.HISTORY_FAST, WorldOperationKind.HISTORY_APPLY.profile());
        assertEquals(WorldApplyProfile.MAXIMUM, WorldOperationKind.LIGHT_REFRESH.profile());
    }

    @Test
    void bulkDiagnosticsUseTurboProfile() {
        assertEquals(WorldOperationKind.DIAGNOSTIC, WorldOperationKind.fromLabel("bulk-diagnostic-sparse-direct-delete"));
    }

    @Test
    void regularOperationsKeepConservativeBudgetProfile() {
        assertEquals(WorldOperationKind.SAVE, WorldOperationKind.fromLabel("save-version"));
        assertEquals(WorldOperationKind.OTHER, WorldOperationKind.fromLabel("background-maintenance"));
        assertEquals(WorldOperationKind.OTHER, WorldOperationKind.fromLabel(null));
    }

    @Test
    void everyHistoryApplyProfileRequiresFinalVerification() {
        assertTrue(WorldOperationKind.HISTORY_APPLY.requiresFinalVerification());
        assertFalse(WorldOperationKind.SAVE.requiresFinalVerification());
        assertFalse(WorldOperationKind.OTHER.requiresFinalVerification());
    }

    @Test
    void savesAndHistoryApplyBlockConcurrentWorldMutations() {
        assertTrue(WorldOperationKind.SAVE.blocksBackgroundMutations());
        assertFalse(WorldOperationKind.SAVE.blocksPreparedMutations());
        assertFalse(WorldOperationKind.HISTORY_APPLY.blocksBackgroundMutations());
        assertTrue(WorldOperationKind.HISTORY_APPLY.blocksPreparedMutations());
        assertFalse(WorldOperationKind.LIGHT_REFRESH.blocksPreparedMutations());
        assertFalse(WorldOperationKind.OTHER.blocksBackgroundMutations());
    }
}
