package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LoadedChunkMutationScopeTest {
    @Test
    void tracksOnlyChunksWhoseLoadEventHasCompleted() {
        LoadedChunkMutationScope scope = new LoadedChunkMutationScope();

        assertFalse(scope.contains(4, -7));

        scope.loaded(4, -7);
        assertTrue(scope.contains(4, -7));

        scope.unloaded(4, -7);
        assertFalse(scope.contains(4, -7));
    }
}
