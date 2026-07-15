package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.minecraft.world.DimensionFreeze;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class DimensionOperationCoordinatorTest {
    @Test
    void acquiresAtTickBoundaryHonorsBudgetAndReleasesSafeTerminalOperation() throws IOException {
        RecordingFreeze freeze = new RecordingFreeze();
        TwoTickMutation mutation = new TwoTickMutation(false);
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 1_000L, 50_000_000L);

        coordinator.start(mutation);
        assertEquals(0, freeze.acquireCalls);

        coordinator.tick();
        assertEquals(1, freeze.acquireCalls);
        assertEquals(50_001_000L, mutation.lastDeadline);
        assertTrue(coordinator.hasActiveOperation());

        coordinator.tick();
        assertEquals(1, freeze.releaseCalls);
        assertTrue(!coordinator.hasActiveOperation());
    }

    @Test
    void rejectsConcurrentMutationAndKeepsDegradedDimensionFrozen() throws IOException {
        RecordingFreeze freeze = new RecordingFreeze();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L);
        coordinator.start(new TwoTickMutation(true));

        assertThrows(IllegalStateException.class,
                () -> coordinator.start(new TwoTickMutation(false)));
        coordinator.tick();
        coordinator.tick();

        assertTrue(coordinator.hasActiveOperation());
        assertEquals(0, freeze.releaseCalls);
    }

    @Test
    void releasesFreezeAfterCaptureWhileKeepingBackgroundOperationOwned() throws IOException {
        RecordingFreeze freeze = new RecordingFreeze();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L);
        coordinator.start(new CaptureThenBackground());

        coordinator.tick();

        assertEquals(1, freeze.releaseCalls);
        assertTrue(coordinator.hasActiveOperation());
        coordinator.tick();
        assertTrue(!coordinator.hasActiveOperation());
    }

    @Test
    void rejectsBudgetsBeyondGlobalTickLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> new DimensionOperationCoordinator(new RecordingFreeze(), () -> 0L, 50_000_001L));
    }

    private static final class TwoTickMutation implements DimensionMutation {
        private final boolean degraded;
        private int ticks;
        private long lastDeadline;

        private TwoTickMutation(boolean degraded) {
            this.degraded = degraded;
        }

        @Override
        public void advance(long deadlineNanos) {
            ticks++;
            lastDeadline = deadlineNanos;
        }

        @Override public boolean isTerminal() { return ticks == 2; }
        @Override public boolean isSafeToRelease() { return !degraded; }
    }

    private static final class CaptureThenBackground implements DimensionMutation {
        private int ticks;

        @Override public void advance(long deadlineNanos) { ticks++; }
        @Override public boolean isTerminal() { return ticks == 2; }
        @Override public boolean isSafeToRelease() { return ticks >= 1; }
    }

    private static final class RecordingFreeze implements DimensionFreeze {
        private int acquireCalls;
        private int releaseCalls;

        @Override
        public Lease acquire() {
            acquireCalls++;
            return () -> releaseCalls++;
        }
    }
}
