package io.github.luma.minecraft.world;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedApplyOperationTest {

    @Test
    void completionDefaultsToBackgroundThread() {
        WorldOperationManager.PreparedApplyOperation operation =
                new WorldOperationManager.PreparedApplyOperation(List.of(), () -> {
                });

        assertFalse(operation.completeOnServerThread());
    }

    @Test
    void callersCanRequestServerThreadCompletionForLightweightActions() {
        WorldOperationManager.PreparedApplyOperation operation =
                new WorldOperationManager.PreparedApplyOperation(List.of(), () -> {
                }, true);

        assertTrue(operation.completeOnServerThread());
    }
}
