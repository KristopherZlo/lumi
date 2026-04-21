package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryCaptureManagerTest {

    @Test
    void shouldCaptureSupportedMutationSources() {
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
    void shouldBootstrapProjectsOnlyFromExplicitSources() {
        assertTrue(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.PLAYER));
        assertTrue(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.ENTITY));
        assertTrue(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.EXPLOSIVE));
        assertTrue(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.EXTERNAL_TOOL));
        assertFalse(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.EXPLOSION));
        assertFalse(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.FLUID));
        assertFalse(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.FIRE));
        assertFalse(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.GROWTH));
        assertFalse(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.BLOCK_UPDATE));
        assertFalse(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.PISTON));
        assertFalse(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.FALLING_BLOCK));
        assertFalse(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.MOB));
        assertFalse(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.SYSTEM));
        assertFalse(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.RESTORE));
        assertFalse(HistoryCaptureManager.allowsAutomaticProjectCreation(null));
    }

    @Test
    void shouldExpandTrackedChunksOnlyFromBuilderDrivenSources() {
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.PLAYER));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.ENTITY));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.EXPLOSION));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.PISTON));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.FALLING_BLOCK));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.EXPLOSIVE));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.EXTERNAL_TOOL));
        assertFalse(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.FLUID));
        assertFalse(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.FIRE));
        assertFalse(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.GROWTH));
        assertFalse(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.BLOCK_UPDATE));
        assertFalse(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.MOB));
        assertFalse(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.SYSTEM));
        assertFalse(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.RESTORE));
        assertFalse(HistoryCaptureManager.allowsTrackedChunkExpansion(null));
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
