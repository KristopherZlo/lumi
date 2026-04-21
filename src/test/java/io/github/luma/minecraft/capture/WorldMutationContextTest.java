package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldMutationContextTest {

    @Test
    void nestedSourcesRestorePreviousSource() {
        assertEquals(WorldMutationSource.SYSTEM, WorldMutationContext.currentSource());

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
}
