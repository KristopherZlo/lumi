package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryCaptureManagerTest {

    @Test
    void shouldCaptureOnlyExplicitMutationSources() {
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.PLAYER));
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.ENTITY));
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.EXPLOSION));
        assertFalse(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.SYSTEM));
        assertFalse(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.RESTORE));
    }

    @Test
    void defaultActorReflectsMutationSource() {
        assertEquals("player", HistoryCaptureManager.defaultActor(WorldMutationSource.PLAYER));
        assertEquals("entity", HistoryCaptureManager.defaultActor(WorldMutationSource.ENTITY));
        assertEquals("explosion", HistoryCaptureManager.defaultActor(WorldMutationSource.EXPLOSION));
        assertEquals("world", HistoryCaptureManager.defaultActor(WorldMutationSource.SYSTEM));
    }
}
