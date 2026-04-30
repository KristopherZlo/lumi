package io.github.luma.minecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldApplyOperationProfileTest {

    private final WorldApplyOperationProfile profile = new WorldApplyOperationProfile();

    @Test
    void highThroughputProfileIncludesUndoRedoAndRecoveryApplyOperations() {
        assertEquals(WorldApplyProfile.HISTORY_FAST, this.profile.profileFor("restore-version"));
        assertEquals(WorldApplyProfile.HISTORY_FAST, this.profile.profileFor("partial-restore"));
        assertEquals(WorldApplyProfile.HISTORY_FAST, this.profile.profileFor("recovery"));
        assertEquals(WorldApplyProfile.HISTORY_FAST, this.profile.profileFor("undo-action"));
        assertEquals(WorldApplyProfile.HISTORY_FAST, this.profile.profileFor("redo-action"));
        assertEquals(WorldApplyProfile.HISTORY_FAST, this.profile.profileFor("merge-variant"));
    }

    @Test
    void bulkDiagnosticsUseTurboProfile() {
        assertEquals(WorldApplyProfile.DIAGNOSTIC_TURBO, this.profile.profileFor("bulk-diagnostic-sparse-direct-delete"));
    }

    @Test
    void regularOperationsKeepConservativeBudgetProfile() {
        assertEquals(WorldApplyProfile.NORMAL, this.profile.profileFor("save-version"));
        assertEquals(WorldApplyProfile.NORMAL, this.profile.profileFor("background-maintenance"));
        assertEquals(WorldApplyProfile.NORMAL, this.profile.profileFor(null));
    }
}
