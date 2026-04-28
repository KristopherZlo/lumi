package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldMutationContextTest {

    @Test
    void externalSourcePreservesToolActorAndActionId() {
        WorldMutationContext.pushExternalSource(WorldMutationSource.WORLDEDIT, "worldedit:builder", "action-1");
        try {
            assertEquals(WorldMutationSource.WORLDEDIT, WorldMutationContext.currentSource());
            assertEquals("worldedit:builder", WorldMutationContext.currentActor());
            assertEquals("action-1", WorldMutationContext.currentActionId());
            assertTrue(WorldMutationContext.currentAccessAllowed());
        } finally {
            WorldMutationContext.popSource();
        }
    }

    @Test
    void ambientSourceDoesNotInheritPlayerActionIdentity() {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "builder", true);
        String playerActionId = WorldMutationContext.currentActionId();
        try {
            WorldMutationContext.pushSource(WorldMutationSource.GROWTH);
            try {
                assertEquals(WorldMutationSource.GROWTH, WorldMutationContext.currentSource());
                assertEquals("growth", WorldMutationContext.currentActor());
                assertEquals("", WorldMutationContext.currentActionId());
                assertFalse(WorldMutationContext.currentAccessAllowed());
            } finally {
                WorldMutationContext.popSource();
            }

            assertEquals(WorldMutationSource.PLAYER, WorldMutationContext.currentSource());
            assertEquals("builder", WorldMutationContext.currentActor());
            assertEquals(playerActionId, WorldMutationContext.currentActionId());
            assertTrue(WorldMutationContext.currentAccessAllowed());
        } finally {
            WorldMutationContext.popSource();
        }
    }

    @Test
    void explicitExplosiveSourceKeepsPlayerActionIdentity() {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "builder", true);
        String playerActionId = WorldMutationContext.currentActionId();
        try {
            WorldMutationContext.pushSource(WorldMutationSource.EXPLOSIVE);
            try {
                assertEquals(WorldMutationSource.EXPLOSIVE, WorldMutationContext.currentSource());
                assertEquals("builder", WorldMutationContext.currentActor());
                assertEquals(playerActionId, WorldMutationContext.currentActionId());
                assertTrue(WorldMutationContext.currentAccessAllowed());
            } finally {
                WorldMutationContext.popSource();
            }
        } finally {
            WorldMutationContext.popSource();
        }
    }
}
