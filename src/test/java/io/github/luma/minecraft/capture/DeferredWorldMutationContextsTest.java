package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredWorldMutationContextsTest {

    @Test
    void rememberSkipsCarriersWithoutActionIdentity() {
        Carrier carrier = new Carrier();

        DeferredWorldMutationContexts.remember(carrier, WorldMutationSource.BLOCK_UPDATE);

        assertNull(carrier.luma$deferredMutationContext());
    }

    @Test
    void pushRestoresCapturedActionIdentity() {
        Carrier carrier = new Carrier();

        try (WorldMutationContext.SourceFrame ignored =
                     WorldMutationContext.pushSource(WorldMutationSource.PLAYER, "builder", "action-1", true)) {
            DeferredWorldMutationContexts.remember(carrier, WorldMutationSource.BLOCK_UPDATE);
        }

        assertEquals(WorldMutationSource.SYSTEM, WorldMutationContext.currentSource());
        DeferredWorldMutationContexts.push(carrier);
        try {
            assertEquals(WorldMutationSource.BLOCK_UPDATE, WorldMutationContext.currentSource());
            assertEquals("builder", WorldMutationContext.currentActor());
            assertEquals("action-1", WorldMutationContext.currentActionId());
            assertTrue(WorldMutationContext.currentAccessAllowed());
        } finally {
            DeferredWorldMutationContexts.pop();
        }
        assertEquals(WorldMutationSource.SYSTEM, WorldMutationContext.currentSource());
    }

    private static final class Carrier implements DeferredWorldMutationContextAccess {

        private DeferredWorldMutationContext context;

        @Override
        public DeferredWorldMutationContext luma$deferredMutationContext() {
            return this.context;
        }

        @Override
        public void luma$setDeferredMutationContext(DeferredWorldMutationContext context) {
            this.context = context;
        }
    }
}
