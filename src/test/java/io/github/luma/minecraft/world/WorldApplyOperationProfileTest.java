package io.github.luma.minecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldApplyOperationProfileTest {

    private final WorldApplyOperationProfile profile = new WorldApplyOperationProfile();

    @Test
    void highThroughputProfileIncludesUndoRedoAndRecoveryApplyOperations() {
        assertTrue(this.profile.isHighThroughput("restore-version"));
        assertTrue(this.profile.isHighThroughput("partial-restore"));
        assertTrue(this.profile.isHighThroughput("recovery"));
        assertTrue(this.profile.isHighThroughput("undo-action"));
        assertTrue(this.profile.isHighThroughput("redo-action"));
        assertTrue(this.profile.isHighThroughput("merge-variant"));
    }

    @Test
    void regularOperationsKeepConservativeBudgetProfile() {
        assertFalse(this.profile.isHighThroughput("save-version"));
        assertFalse(this.profile.isHighThroughput("background-maintenance"));
        assertFalse(this.profile.isHighThroughput(null));
    }
}
