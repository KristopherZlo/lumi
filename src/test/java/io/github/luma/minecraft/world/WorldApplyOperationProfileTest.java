package io.github.luma.minecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldApplyOperationProfileTest {

    private final WorldApplyOperationProfile profile = new WorldApplyOperationProfile();

    @Test
    void historyApplyOperationsUseFastButBoundedProfile() {
        assertEquals(WorldApplyProfile.HISTORY_FAST, this.profile.profileFor("restore-version"));
        assertEquals(WorldApplyProfile.HISTORY_FAST, this.profile.profileFor("partial-restore"));
        assertEquals(WorldApplyProfile.HISTORY_FAST, this.profile.profileFor("zone-restore"));
        assertEquals(WorldApplyProfile.HISTORY_FAST, this.profile.profileFor("recovery"));
        assertEquals(WorldApplyProfile.HISTORY_FAST, this.profile.profileFor("restore-draft"));
        assertEquals(WorldApplyProfile.HISTORY_FAST, this.profile.profileFor("quick-rollback"));
        assertEquals(WorldApplyProfile.HISTORY_FAST, this.profile.profileFor("undo-action"));
        assertEquals(WorldApplyProfile.HISTORY_FAST, this.profile.profileFor("redo-action"));
        assertEquals(WorldApplyProfile.HISTORY_FAST, this.profile.profileFor("merge-variant"));
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

    @Test
    void everyHistoryApplyProfileRequiresFinalVerification() {
        assertTrue(this.profile.requiresPostApplyVerification("restore-version"));
        assertTrue(this.profile.requiresPostApplyVerification("partial-restore"));
        assertTrue(this.profile.requiresPostApplyVerification("zone-restore"));
        assertTrue(this.profile.requiresPostApplyVerification("recovery"));
        assertTrue(this.profile.requiresPostApplyVerification("restore-draft"));
        assertTrue(this.profile.requiresPostApplyVerification("quick-rollback"));
        assertTrue(this.profile.requiresPostApplyVerification("undo-action"));
        assertTrue(this.profile.requiresPostApplyVerification("redo-action"));
        assertTrue(this.profile.requiresPostApplyVerification("merge-variant"));
        assertFalse(this.profile.requiresPostApplyVerification("save-version"));
        assertFalse(this.profile.requiresPostApplyVerification(null));
    }
}
