package io.github.luma.minecraft.world;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WorldApplyChunkLoadContextTest {

    @Test
    void defaultsToDisallowingSynchronousChunkLoads() {
        Assertions.assertFalse(WorldApplyChunkLoadContext.allowsSynchronousLoad());
    }

    @Test
    void supportsNestedSynchronousChunkLoadScopes() {
        WorldApplyChunkLoadContext.pushAllowSynchronousLoad();
        WorldApplyChunkLoadContext.pushAllowSynchronousLoad();

        Assertions.assertTrue(WorldApplyChunkLoadContext.allowsSynchronousLoad());

        WorldApplyChunkLoadContext.pop();
        Assertions.assertTrue(WorldApplyChunkLoadContext.allowsSynchronousLoad());

        WorldApplyChunkLoadContext.pop();
        Assertions.assertFalse(WorldApplyChunkLoadContext.allowsSynchronousLoad());
    }

    @Test
    void extraPopLeavesContextDisabled() {
        WorldApplyChunkLoadContext.pop();

        Assertions.assertFalse(WorldApplyChunkLoadContext.allowsSynchronousLoad());
    }
}
