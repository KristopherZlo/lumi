package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldMutationContextTest {

    @Test
    void nestedSourcesRestorePreviousSource() {
        assertEquals(WorldMutationSource.SYSTEM, WorldMutationContext.currentSource());
        assertFalse(WorldMutationContext.currentAccessAllowed());

        WorldMutationContext.runWithSource(WorldMutationSource.FALLING_BLOCK, () -> {
            assertEquals(WorldMutationSource.FALLING_BLOCK, WorldMutationContext.currentSource());
            WorldMutationContext.runWithSource(WorldMutationSource.EXPLOSIVE, () -> assertEquals(
                    WorldMutationSource.EXPLOSIVE,
                    WorldMutationContext.currentSource()
            ));
            assertEquals(WorldMutationSource.FALLING_BLOCK, WorldMutationContext.currentSource());
        });

        assertEquals(WorldMutationSource.SYSTEM, WorldMutationContext.currentSource());
    }

    @Test
    void playerSourceCarriesActorAccessAndActionIntoNestedSources() {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, "Alex", true);
        try {
            String actionId = WorldMutationContext.currentActionId();
            assertEquals("Alex", WorldMutationContext.currentActor());
            assertFalse(actionId.isBlank());
            assertTrue(WorldMutationContext.currentAccessAllowed());

            WorldMutationContext.pushSource(WorldMutationSource.EXPLOSIVE);
            try {
                assertEquals(WorldMutationSource.EXPLOSIVE, WorldMutationContext.currentSource());
                assertEquals("Alex", WorldMutationContext.currentActor());
                assertEquals(actionId, WorldMutationContext.currentActionId());
                assertTrue(WorldMutationContext.currentAccessAllowed());
            } finally {
                WorldMutationContext.popSource();
            }
        } finally {
            WorldMutationContext.popSource();
        }
    }
}
