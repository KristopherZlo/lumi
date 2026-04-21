package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryCaptureManagerTest {

    @Test
    void shouldCaptureAllNonRestoreSources() {
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.PLAYER));
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.SYSTEM));
        assertFalse(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.RESTORE));
    }

    @Test
    void defaultActorUsesWorldLabelForNonPlayerMutations() {
        assertEquals("player", HistoryCaptureManager.defaultActor(WorldMutationSource.PLAYER));
        assertEquals("world", HistoryCaptureManager.defaultActor(WorldMutationSource.SYSTEM));
    }
}
