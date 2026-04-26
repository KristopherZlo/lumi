package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.ChunkPoint;
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
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.WORLDEDIT));
        assertTrue(HistoryCaptureManager.shouldCaptureMutation(WorldMutationSource.AXIOM));
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
        assertTrue(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.WORLDEDIT));
        assertTrue(HistoryCaptureManager.allowsAutomaticProjectCreation(WorldMutationSource.AXIOM));
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
    void shouldBootstrapSessionsOnlyFromExplicitSources() {
        assertTrue(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.PLAYER));
        assertTrue(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.ENTITY));
        assertTrue(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.EXPLOSIVE));
        assertTrue(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.EXTERNAL_TOOL));
        assertTrue(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.WORLDEDIT));
        assertTrue(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.AXIOM));
        assertFalse(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.EXPLOSION));
        assertFalse(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.FLUID));
        assertFalse(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.FIRE));
        assertFalse(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.GROWTH));
        assertFalse(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.BLOCK_UPDATE));
        assertFalse(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.PISTON));
        assertFalse(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.FALLING_BLOCK));
        assertFalse(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.MOB));
        assertFalse(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.SYSTEM));
        assertFalse(HistoryCaptureManager.allowsSessionBootstrap(WorldMutationSource.RESTORE));
        assertFalse(HistoryCaptureManager.allowsSessionBootstrap(null));
    }

    @Test
    void shouldExpandTrackedChunksOnlyFromBuilderDrivenSources() {
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.PLAYER));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.ENTITY));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.EXPLOSION));
        assertFalse(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.FLUID));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.FIRE));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.GROWTH));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.BLOCK_UPDATE));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.PISTON));
        assertFalse(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.FALLING_BLOCK));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.MOB));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.EXPLOSIVE));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.EXTERNAL_TOOL));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.WORLDEDIT));
        assertTrue(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.AXIOM));
        assertFalse(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.SYSTEM));
        assertFalse(HistoryCaptureManager.allowsTrackedChunkExpansion(WorldMutationSource.RESTORE));
        assertFalse(HistoryCaptureManager.allowsTrackedChunkExpansion(null));
    }

    @Test
    void shouldGateSecondarySourcesByActiveRegion() {
        assertTrue(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.EXPLOSION));
        assertTrue(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.FLUID));
        assertTrue(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.FIRE));
        assertTrue(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.GROWTH));
        assertTrue(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.BLOCK_UPDATE));
        assertTrue(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.PISTON));
        assertTrue(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.FALLING_BLOCK));
        assertTrue(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.MOB));
        assertFalse(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.PLAYER));
        assertFalse(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.ENTITY));
        assertFalse(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.EXPLOSIVE));
        assertFalse(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.EXTERNAL_TOOL));
        assertFalse(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.WORLDEDIT));
        assertFalse(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.AXIOM));
        assertFalse(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.SYSTEM));
        assertFalse(HistoryCaptureManager.requiresActiveRegionMembership(WorldMutationSource.RESTORE));
        assertFalse(HistoryCaptureManager.requiresActiveRegionMembership(null));
    }

    @Test
    void shouldMeasureChunkRadiusUsingSquareDistance() {
        assertTrue(HistoryCaptureManager.isWithinChunkRadius(new ChunkPoint(1, 0), new ChunkPoint(1, 0), 0));
        assertTrue(HistoryCaptureManager.isWithinChunkRadius(new ChunkPoint(1, 0), new ChunkPoint(3, 2), 2));
        assertFalse(HistoryCaptureManager.isWithinChunkRadius(new ChunkPoint(1, 0), new ChunkPoint(4, 0), 2));
        assertFalse(HistoryCaptureManager.isWithinChunkRadius(new ChunkPoint(1, 0), new ChunkPoint(3, 3), 1));
        assertFalse(HistoryCaptureManager.isWithinChunkRadius(new ChunkPoint(1, 0), null, 2));
        assertFalse(HistoryCaptureManager.isWithinChunkRadius(null, new ChunkPoint(3, 3), 2));
        assertFalse(HistoryCaptureManager.isWithinChunkRadius(new ChunkPoint(1, 0), new ChunkPoint(1, 0), -1));
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
        assertEquals("worldedit", HistoryCaptureManager.defaultActor(WorldMutationSource.WORLDEDIT));
        assertEquals("axiom", HistoryCaptureManager.defaultActor(WorldMutationSource.AXIOM));
        assertEquals("world", HistoryCaptureManager.defaultActor(WorldMutationSource.SYSTEM));
        assertEquals("world", HistoryCaptureManager.defaultActor(null));
    }
}
