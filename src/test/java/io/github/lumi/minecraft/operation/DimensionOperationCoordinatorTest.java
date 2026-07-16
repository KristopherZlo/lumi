package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.minecraft.world.DimensionFreeze;
import java.io.IOException;
import java.util.ArrayList;
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
    void ownsBackgroundPreparationWithoutFreezingUntilApplyIsReady() throws IOException {
        RecordingFreeze freeze = new RecordingFreeze();
        DeferredFreezeMutation mutation = new DeferredFreezeMutation();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L);
        coordinator.start(mutation);

        coordinator.tick();
        assertEquals(0, freeze.acquireCalls);
        assertTrue(coordinator.hasActiveOperation());

        mutation.ready = true;
        coordinator.tick();
        assertEquals(1, freeze.acquireCalls);
        assertEquals(1, freeze.releaseCalls);
        assertTrue(!coordinator.hasActiveOperation());
    }

    @Test
    void rejectsBudgetsBeyondGlobalTickLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> new DimensionOperationCoordinator(new RecordingFreeze(), () -> 0L, 50_000_001L));
    }

    @Test
    void reportsRetainedDegradedOutcomeExactlyOnce() throws IOException {
        RecordingFreeze freeze = new RecordingFreeze();
        var outcomes = new ArrayList<MutationTerminalState>();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L,
                mutation -> outcomes.add(mutation.terminalState()));
        coordinator.start(new TwoTickMutation(true));

        coordinator.tick();
        coordinator.tick();
        coordinator.tick();

        assertEquals(java.util.List.of(MutationTerminalState.DEGRADED), outcomes);
        assertTrue(coordinator.hasActiveOperation());
        assertEquals(0, freeze.releaseCalls);
    }

    @Test
    void reportsTerminalOutcomeToGlobalAndRequestObserver() throws IOException {
        var global = new ArrayList<DimensionMutation>();
        var request = new ArrayList<DimensionMutation>();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                new RecordingFreeze(), () -> 0L, 1L, global::add);
        TwoTickMutation mutation = new TwoTickMutation(false);

        coordinator.start(mutation, request::add);
        coordinator.tick();
        coordinator.tick();

        assertEquals(java.util.List.of(mutation), global);
        assertEquals(java.util.List.of(mutation), request);
    }

    @Test
    void adoptsRecoveryFreezeWithoutAnUnfrozenGap() throws IOException {
        RecordingFreeze freeze = new RecordingFreeze();
        DimensionFreeze.Lease recoveryLease = freeze.acquire();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L);

        coordinator.startWithLease(new TwoTickMutation(false), recoveryLease);
        coordinator.tick();
        coordinator.tick();

        assertEquals(1, freeze.acquireCalls);
        assertEquals(1, freeze.releaseCalls);
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

        @Override public boolean isTerminal() { return ticks >= 2; }
        @Override public boolean isSafeToRelease() { return !degraded; }
    }

    private static final class CaptureThenBackground implements DimensionMutation {
        private int ticks;

        @Override public void advance(long deadlineNanos) { ticks++; }
        @Override public boolean isTerminal() { return ticks == 2; }
        @Override public boolean isSafeToRelease() { return ticks >= 1; }
    }

    private static final class DeferredFreezeMutation implements DimensionMutation {
        private boolean ready;
        private boolean complete;

        @Override public boolean requiresFreeze() { return ready; }
        @Override public void advance(long deadlineNanos) { complete = ready; }
        @Override public boolean isTerminal() { return complete; }
        @Override public boolean isSafeToRelease() { return complete; }
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
