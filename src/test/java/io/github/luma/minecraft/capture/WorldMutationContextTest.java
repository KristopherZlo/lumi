package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
