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
    void queuesConcurrentMutationAndKeepsDegradedDimensionFrozen() throws IOException {
        RecordingFreeze freeze = new RecordingFreeze();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L);
        coordinator.start(new TwoTickMutation(true));

        coordinator.start(new TwoTickMutation(false));
        coordinator.tick();
        coordinator.tick();

        assertTrue(coordinator.hasActiveOperation());
        assertEquals(1, coordinator.queuedCount());
        assertEquals(0, freeze.releaseCalls);
    }

    @Test
    void urgentUndoRunsBeforeQueuedNormalSaveWithoutInterruptingActiveApply()
            throws IOException {
        var order = new ArrayList<String>();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                new RecordingFreeze(), () -> 0L, 1L);
        coordinator.start(new NamedMutation("active", order, 2));
        OperationTicket save = coordinator.enqueue(
                new NamedMutation("save", order, 1), OperationPriority.NORMAL, ignored -> { });
        OperationTicket undo = coordinator.enqueue(
                new NamedMutation("undo", order, 1), OperationPriority.URGENT, ignored -> { });

        assertEquals(2, coordinator.queuePosition(save).orElseThrow());
        assertEquals(1, coordinator.queuePosition(undo).orElseThrow());
        coordinator.tick();
        coordinator.tick();
        coordinator.tick();
        coordinator.tick();

        assertEquals(java.util.List.of("active", "active", "undo", "save"), order);
        assertTrue(!coordinator.hasActiveOperation());
    }

    @Test
    void queuedOperationCanBeCancelledBeforeItStarts() {
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                new RecordingFreeze(), () -> 0L, 1L);
        coordinator.start(new TwoTickMutation(false));
        OperationTicket queued = coordinator.enqueue(
                new TwoTickMutation(false), OperationPriority.NORMAL, ignored -> { });

        assertTrue(coordinator.cancelQueued(queued));
        assertEquals(0, coordinator.queuedCount());
        assertTrue(!coordinator.cancelQueued(queued));
    }

    @Test
    void rejectsWorkBeyondTheBoundedQueue() {
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                new RecordingFreeze(), () -> 0L, 1L);
        coordinator.start(new TwoTickMutation(false));
        for (int index = 0; index < DimensionOperationCoordinator.MAX_QUEUED_OPERATIONS; index++) {
            coordinator.enqueue(
                    new TwoTickMutation(false), OperationPriority.NORMAL, ignored -> { });
        }

        assertThrows(IllegalStateException.class, () -> coordinator.enqueue(
                new TwoTickMutation(false), OperationPriority.NORMAL, ignored -> { }));
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

    private static final class NamedMutation implements DimensionMutation {
        private final String name;
        private final ArrayList<String> order;
        private final int requiredTicks;
        private int ticks;

        private NamedMutation(String name, ArrayList<String> order, int requiredTicks) {
            this.name = name;
            this.order = order;
            this.requiredTicks = requiredTicks;
        }

        @Override public void advance(long deadlineNanos) {
            order.add(name);
            ticks++;
        }

        @Override public boolean isTerminal() { return ticks >= requiredTicks; }
        @Override public boolean isSafeToRelease() { return isTerminal(); }
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
