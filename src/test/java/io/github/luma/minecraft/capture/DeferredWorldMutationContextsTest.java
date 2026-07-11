package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredWorldMutationContextsTest {

    @Test
    void rememberSkipsCarriersWithoutCapturableSource() {
        Carrier carrier = new Carrier();

        DeferredWorldMutationContexts.remember(carrier, WorldMutationSource.BLOCK_UPDATE);

        assertNull(carrier.luma$deferredMutationContext());
    }

    @Test
    void pushRestoresCapturedMutationContextWithoutActionOwnership() {
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
            assertEquals("", WorldMutationContext.currentActionId());
            assertTrue(WorldMutationContext.currentAccessAllowed());
        } finally {
            DeferredWorldMutationContexts.pop();
        }
        assertEquals(WorldMutationSource.SYSTEM, WorldMutationContext.currentSource());
    }

    @Test
    void mechanismContextPropagationSurvivesDeepRedstoneCascadesButStopsEventually() {
        Carrier current = new Carrier();

        try (WorldMutationContext.SourceFrame ignored =
                     WorldMutationContext.pushSource(WorldMutationSource.PLAYER, "builder", "action-1", true)) {
            DeferredWorldMutationContexts.remember(current, WorldMutationSource.BLOCK_UPDATE);
        }
        assertEquals(1, current.luma$deferredMutationContext().propagationDepth());

        for (int depth = 2; depth <= DeferredWorldMutationContext.MAX_MECHANISM_PROPAGATION_DEPTH; depth++) {
            Carrier next = new Carrier();
            DeferredWorldMutationContexts.push(current);
            try {
                DeferredWorldMutationContexts.remember(next, WorldMutationSource.BLOCK_UPDATE);
            } finally {
                DeferredWorldMutationContexts.pop();
            }
            assertEquals(depth, next.luma$deferredMutationContext().propagationDepth());
            assertEquals("builder", next.luma$deferredMutationContext().actor());
            current = next;
        }

        Carrier overflow = new Carrier();
        DeferredWorldMutationContexts.push(current);
        try {
            DeferredWorldMutationContexts.remember(overflow, WorldMutationSource.BLOCK_UPDATE);
        } finally {
            DeferredWorldMutationContexts.pop();
        }
        assertNull(overflow.luma$deferredMutationContext());
    }

    @Test
    void pistonMovementCarrierPreservesContextWithoutIncreasingMechanismDepth() {
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
        assertEquals("builder", third.luma$deferredMutationContext().actor());
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
