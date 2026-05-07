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

    @Test
    void mechanismContextPropagationIsBounded() {
        Carrier first = new Carrier();
        Carrier second = new Carrier();
        Carrier third = new Carrier();

        try (WorldMutationContext.SourceFrame ignored =
                     WorldMutationContext.pushSource(WorldMutationSource.PLAYER, "builder", "action-1", true)) {
            DeferredWorldMutationContexts.remember(first, WorldMutationSource.BLOCK_UPDATE);
        }

        DeferredWorldMutationContexts.push(first);
        try {
            DeferredWorldMutationContexts.remember(second, WorldMutationSource.PISTON);
        } finally {
            DeferredWorldMutationContexts.pop();
        }

        assertEquals(2, second.luma$deferredMutationContext().propagationDepth());

        DeferredWorldMutationContexts.push(second);
        try {
            DeferredWorldMutationContexts.remember(third, WorldMutationSource.BLOCK_UPDATE);
        } finally {
            DeferredWorldMutationContexts.pop();
        }

        assertNull(third.luma$deferredMutationContext());
    }

    @Test
    void pistonMovementCarrierPreservesActionWithoutIncreasingMechanismDepth() {
        Carrier first = new Carrier();
        Carrier second = new Carrier();
        Carrier third = new Carrier();

        try (WorldMutationContext.SourceFrame ignored =
                     WorldMutationContext.pushSource(WorldMutationSource.PLAYER, "builder", "action-1", true)) {
            DeferredWorldMutationContexts.remember(first, WorldMutationSource.BLOCK_UPDATE);
        }

        DeferredWorldMutationContexts.push(first);
        try {
            DeferredWorldMutationContexts.rememberPistonMovement(second);
        } finally {
            DeferredWorldMutationContexts.pop();
        }

        assertEquals(1, second.luma$deferredMutationContext().propagationDepth());

        DeferredWorldMutationContexts.push(second);
        try {
            DeferredWorldMutationContexts.rememberPistonMovement(third);
        } finally {
            DeferredWorldMutationContexts.pop();
        }

        assertEquals(WorldMutationSource.PISTON, third.luma$deferredMutationContext().source());
        assertEquals("action-1", third.luma$deferredMutationContext().actionId());
        assertEquals(1, third.luma$deferredMutationContext().propagationDepth());
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
