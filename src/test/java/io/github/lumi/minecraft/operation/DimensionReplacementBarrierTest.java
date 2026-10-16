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
        var nextWrites = new AtomicInteger();
        coordinator.start(new DimensionMutation() {
            @Override public void advance(long deadline) { nextWrites.incrementAndGet(); }
            @Override public boolean isTerminal() { return nextWrites.get() > 0; }
            @Override public boolean isSafeToRelease() { return isTerminal(); }
        });
        coordinator.tick();
        coordinator.tick();
        assertTrue(freeze.isFrozen());
        assertTrue(coordinator.hasActiveOperation());
        assertEquals(0, terminals.get());
        assertEquals(0, nextWrites.get());
        assertEquals(1, freeze.replacementGeneration());
        barrier.releaseReady = true;
        coordinator.tick();
        assertFalse(freeze.isFrozen());
        assertEquals(0, nextWrites.get());
        coordinator.tick();
        assertFalse(coordinator.hasActiveOperation());
        assertEquals(2, terminals.get());
        assertEquals(1, nextWrites.get());
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
