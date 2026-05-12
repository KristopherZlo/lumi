package io.github.luma.minecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldApplyOperationProfileTest {

    private final WorldApplyOperationProfile profile = new WorldApplyOperationProfile();

    @Test
    void maximumProfileIncludesUndoRedoAndRecoveryApplyOperations() {
        assertEquals(WorldApplyProfile.MAXIMUM, this.profile.profileFor("restore-version"));
        assertEquals(WorldApplyProfile.MAXIMUM, this.profile.profileFor("partial-restore"));
        assertEquals(WorldApplyProfile.MAXIMUM, this.profile.profileFor("recovery"));
        assertEquals(WorldApplyProfile.MAXIMUM, this.profile.profileFor("quick-rollback"));
        assertEquals(WorldApplyProfile.MAXIMUM, this.profile.profileFor("undo-action"));
        assertEquals(WorldApplyProfile.MAXIMUM, this.profile.profileFor("redo-action"));
        assertEquals(WorldApplyProfile.MAXIMUM, this.profile.profileFor("merge-variant"));
        assertEquals(WorldApplyProfile.MAXIMUM, this.profile.profileFor("light-refresh"));
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
