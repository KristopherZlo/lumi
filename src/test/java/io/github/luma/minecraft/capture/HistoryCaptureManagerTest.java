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
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.FLUID));
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.FIRE));
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.GROWTH));
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.BLOCK_UPDATE));
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.PISTON));
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.FALLING_BLOCK));
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.EXPLOSIVE));
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.MOB));
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.EXTERNAL_TOOL));
        assertFalse(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.SYSTEM));
        assertFalse(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.RESTORE));
        assertFalse(HistoryCaptureManager.shouldCaptureMutation(null));
    }

    @Test
    void defaultActorReflectsMutationSource() {
        assertEquals("player", HistoryCaptureManager.defaultActor(WorldMutationSource.PLAYER));
        assertEquals("entity", HistoryCaptureManager.defaultActor(WorldMutationSource.ENTITY));
        assertEquals("explosion", HistoryCaptureManager.defaultActor(WorldMutationSource.EXPLOSION));
        assertEquals("fluid", HistoryCaptureManager.defaultActor(WorldMutationSource.FLUID));
        assertEquals("fire", HistoryCaptureManager.defaultActor(WorldMutationSource.FIRE));
        assertEquals("growth", HistoryCaptureManager.defaultActor(WorldMutationSource.GROWTH));
        assertEquals("block-update", HistoryCaptureManager.defaultActor(WorldMutationSource.BLOCK_UPDATE));
        assertEquals("piston", HistoryCaptureManager.defaultActor(WorldMutationSource.PISTON));
        assertEquals("falling-block", HistoryCaptureManager.defaultActor(WorldMutationSource.FALLING_BLOCK));
        assertEquals("explosive", HistoryCaptureManager.defaultActor(WorldMutationSource.EXPLOSIVE));
        assertEquals("mob", HistoryCaptureManager.defaultActor(WorldMutationSource.MOB));
        assertEquals("external-tool", HistoryCaptureManager.defaultActor(WorldMutationSource.EXTERNAL_TOOL));
        assertEquals("world", HistoryCaptureManager.defaultActor(WorldMutationSource.SYSTEM));
        assertEquals("world", HistoryCaptureManager.defaultActor(null));
    }
}
