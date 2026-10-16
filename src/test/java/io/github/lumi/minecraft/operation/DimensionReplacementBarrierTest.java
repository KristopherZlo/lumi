package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.minecraft.world.DimensionFreezeState;
import io.github.lumi.minecraft.world.WorldReplacementBarrier;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DimensionReplacementBarrierTest {
    @Test void lateEditorsKeepTheLeaseAndTerminalNotificationUntilTheySynchronize() throws IOException {
        var freeze = new DimensionFreezeState();
        var barrier = new Barrier();
        freeze.replacementBarrier(barrier);
        var terminals = new AtomicInteger();
        var coordinator = new DimensionOperationCoordinator(freeze, ignored -> terminals.incrementAndGet());
        coordinator.start(new DimensionMutation() {
            private boolean complete;
            @Override public void advance(long deadline) throws IOException { complete = freeze.awaitReplacementAdmission(); }
            @Override public boolean isTerminal() { return complete; }
            @Override public boolean isSafeToRelease() { return complete; }
        });
        coordinator.tick();
        assertTrue(freeze.isFrozen());
        assertTrue(coordinator.hasActiveOperation());
        assertEquals(0, terminals.get());
        assertEquals(1, freeze.replacementGeneration());
        barrier.releaseReady = true;
        coordinator.tick();
        assertFalse(freeze.isFrozen());
        assertFalse(coordinator.hasActiveOperation());
        assertEquals(1, terminals.get());
        assertEquals(1, barrier.resumptions);
    }

    private static final class Barrier implements WorldReplacementBarrier {
        private boolean releaseReady;
        private int resumptions;
        @Override public boolean awaitReady() { return true; }
        @Override public boolean isReleaseReady() { return releaseReady; }
        @Override public void released() { resumptions++; }
    }
}
