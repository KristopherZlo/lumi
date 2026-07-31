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
        var savePositions = new ArrayList<Integer>();
        coordinator.observeQueuePosition(save, savePositions::add);
        OperationTicket undo = coordinator.enqueue(
                new NamedMutation("undo", order, 1), OperationPriority.URGENT, ignored -> { });

        assertEquals(2, coordinator.queuePosition(save).orElseThrow());
        assertEquals(1, coordinator.queuePosition(undo).orElseThrow());
        coordinator.tick();
        coordinator.tick();
        coordinator.tick();
        coordinator.tick();

        assertEquals(java.util.List.of("active", "active", "undo", "save"), order);
        assertEquals(java.util.List.of(1, 2, 1, 0), savePositions);
        assertTrue(!coordinator.hasActiveOperation());
    }

    @Test
    void queuedOperationCanBeCancelledBeforeItStarts() throws Exception {
        var global = new ArrayList<MutationTerminalState>();
        var request = new ArrayList<MutationTerminalState>();
        var ticket = new ArrayList<MutationTerminalState>();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                new RecordingFreeze(), () -> 0L, 1L,
                operation -> global.add(operation.terminalState()));
        TwoTickMutation activeMutation = new TwoTickMutation(false);
        coordinator.start(activeMutation);
        OperationTicket active = coordinator.ticketOf(activeMutation).orElseThrow();
        OperationTicket queued = coordinator.enqueue(
                new CancellableMutation(), OperationPriority.NORMAL,
                operation -> request.add(operation.terminalState()));
        coordinator.observeTerminal(
                queued, operation -> ticket.add(operation.terminalState()));

        assertTrue(!coordinator.cancelQueued(active));
        assertTrue(coordinator.cancelQueued(queued));
        assertEquals(0, coordinator.queuedCount());
        assertEquals(java.util.List.of(MutationTerminalState.CANCELLED), global);
        assertEquals(java.util.List.of(MutationTerminalState.CANCELLED), request);
        assertEquals(java.util.List.of(MutationTerminalState.CANCELLED), ticket);
        assertTrue(!coordinator.cancel(queued));
    }

    @Test
    void cancelsSafeActiveOperationAndReleasesItsFreeze() throws Exception {
        RecordingFreeze freeze = new RecordingFreeze();
        var outcomes = new ArrayList<MutationTerminalState>();
        CancellableMutation mutation = new CancellableMutation();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L,
                operation -> outcomes.add(operation.terminalState()));
        coordinator.start(mutation);
        OperationTicket ticket = coordinator.ticketOf(mutation).orElseThrow();
        coordinator.tick();

        assertTrue(coordinator.cancel(ticket));

        assertEquals(java.util.List.of(MutationTerminalState.CANCELLED), outcomes);
        assertEquals(1, freeze.releaseCalls);
        assertTrue(!coordinator.hasActiveOperation());
    }

    @Test
    void closeReleasesFreezeAndClosesActiveAndQueuedResources() throws Exception {
        RecordingFreeze freeze = new RecordingFreeze();
        CloseTrackingMutation active = new CloseTrackingMutation();
        CloseTrackingMutation queued = new CloseTrackingMutation();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L);
        coordinator.start(active);
        coordinator.enqueue(queued, OperationPriority.NORMAL, ignored -> { });
        coordinator.tick();

        coordinator.close();

        assertEquals(1, active.closeCalls);
        assertEquals(1, queued.closeCalls);
        assertEquals(1, freeze.releaseCalls);
        assertTrue(!coordinator.hasActiveOperation());
        assertEquals(0, coordinator.queuedCount());
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
        assertEquals(30_000_000L,
                DimensionOperationCoordinator.DEFAULT_TICK_WORK_NANOS);
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
    void containsUnhandledFailureAsDegradedAndRetainsFreeze() throws IOException {
        RecordingFreeze freeze = new RecordingFreeze();
        var outcomes = new ArrayList<DimensionMutation>();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L, outcomes::add);

        coordinator.start(new ThrowingMutation(MutationTerminalState.DEGRADED));
        coordinator.tick();
        coordinator.tick();

        assertEquals(1, outcomes.size());
        assertEquals(MutationTerminalState.DEGRADED, outcomes.getFirst().terminalState());
        assertEquals("advance failed", outcomes.getFirst().failure().orElseThrow().getMessage());
        assertTrue(coordinator.hasActiveOperation());
        assertEquals(0, freeze.releaseCalls);
    }

    @Test
    void containsSafeUnhandledFailureAndContinuesWithQueuedWork() throws IOException {
        RecordingFreeze freeze = new RecordingFreeze();
        var outcomes = new ArrayList<MutationTerminalState>();
        var order = new ArrayList<String>();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L,
                mutation -> outcomes.add(mutation.terminalState()));

        coordinator.start(new ThrowingMutation(MutationTerminalState.FAILED));
        coordinator.start(new NamedMutation("next", order, 1));
        coordinator.tick();
        coordinator.tick();

        assertEquals(java.util.List.of(
                MutationTerminalState.FAILED, MutationTerminalState.SUCCEEDED), outcomes);
        assertEquals(java.util.List.of("next"), order);
        assertEquals(2, freeze.releaseCalls);
        assertTrue(!coordinator.hasActiveOperation());
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
    void observerFailuresCannotRetainTheFreezeOrTheOperation() throws IOException {
        RecordingFreeze freeze = new RecordingFreeze();
        var failures = new ArrayList<RuntimeException>();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L,
                ignored -> {
                    throw new IllegalStateException("global failed");
                },
                failures::add);
        TwoTickMutation mutation = new TwoTickMutation(false);
        coordinator.start(mutation, ignored -> {
            throw new IllegalStateException("request failed");
        });

        coordinator.tick();
        coordinator.tick();

        assertEquals(2, failures.size());
        assertEquals(1, freeze.releaseCalls);
        assertTrue(!coordinator.hasActiveOperation());
    }

    @Test
    void reportsFrozenBoundaryBeforeTheFirstMutationStep() throws IOException {
        var order = new ArrayList<String>();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                new RecordingFreeze(), () -> 0L, 1L);
        NamedMutation mutation = new NamedMutation("advance", order, 1);
        coordinator.start(mutation);
        OperationTicket ticket = coordinator.ticketOf(mutation).orElseThrow();
        coordinator.observeFreezeAcquired(ticket, () -> order.add("frozen"));

        coordinator.tick();

        assertEquals(java.util.List.of("frozen", "advance"), order);
        assertTrue(!coordinator.hasActiveOperation());
    }

    @Test
    void nextEnqueueObserverCanAttachFrozenAndTerminalBoundaries() throws IOException {
        var order = new ArrayList<String>();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                new RecordingFreeze(), () -> 0L, 1L);
        NamedMutation mutation = new NamedMutation("advance", order, 1);
        coordinator.observeNextEnqueue((ticket, accepted) -> {
            assertTrue(accepted == mutation);
            order.add("enqueued");
            coordinator.observeFreezeAcquired(ticket, () -> order.add("frozen"));
            coordinator.observeTerminal(ticket, ignored -> order.add("terminal"));
            coordinator.observeBeforeFreezeRelease(ticket, () -> order.add("release"));
        });

        coordinator.start(mutation);
        coordinator.tick();

        assertEquals(java.util.List.of(
                "enqueued", "frozen", "advance", "terminal", "release"), order);
        assertTrue(!coordinator.hasActiveOperation());
    }

    @Test
    void failingProgressObserverIsRemovedBeforeTheNextTick() throws IOException {
        RecordingFreeze freeze = new RecordingFreeze();
        var failures = new ArrayList<RuntimeException>();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L, ignored -> { }, failures::add);
        TwoTickMutation mutation = new TwoTickMutation(false);
        coordinator.start(mutation);
        OperationTicket ticket = coordinator.ticketOf(mutation).orElseThrow();
        coordinator.observeProgress(ticket, ignored -> { });
        coordinator.observeProgress(ticket, ignored -> {
            throw new IllegalStateException("progress failed");
        });

        coordinator.tick();
        coordinator.tick();

        assertEquals(1, failures.size());
        assertEquals(1, freeze.releaseCalls);
        assertTrue(!coordinator.hasActiveOperation());
    }

    @Test
    void releasesOperationThatIsAlreadyTerminalWhenActivated() throws IOException {
        var outcomes = new ArrayList<MutationTerminalState>();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                new RecordingFreeze(), () -> 0L, 1L,
                mutation -> outcomes.add(mutation.terminalState()));

        coordinator.start(new AlreadyTerminalMutation());
        coordinator.tick();

        assertEquals(java.util.List.of(MutationTerminalState.SUCCEEDED), outcomes);
        assertTrue(!coordinator.hasActiveOperation());
    }

    @Test
    void publishesOnlyChangedImmutableProgressSnapshots() throws IOException {
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                new RecordingFreeze(), () -> 0L, 1L);
        NamedMutation mutation = new NamedMutation("save", new ArrayList<>(), 2);
        coordinator.start(mutation);
        OperationTicket ticket = coordinator.ticketOf(mutation).orElseThrow();
        var progress = new ArrayList<OperationProgress>();
        coordinator.observeProgress(ticket, progress::add);

        coordinator.tick();
        coordinator.tick();

        assertEquals(java.util.List.of(
                new OperationProgress("save", 0, 2),
                new OperationProgress("save", 1, 2),
                new OperationProgress("save", 2, 2)), progress);
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
        @Override public OperationProgress progress() {
            return new OperationProgress(name, Math.min(ticks, requiredTicks), requiredTicks);
        }
    }

    private static final class AlreadyTerminalMutation implements DimensionMutation {
        @Override public void advance(long deadlineNanos) { }
        @Override public boolean isTerminal() { return true; }
        @Override public boolean isSafeToRelease() { return true; }
    }

    private static final class CancellableMutation implements DimensionMutation {
        private boolean cancelled;

        @Override public void advance(long deadlineNanos) { }
        @Override public boolean cancel() {
            cancelled = true;
            return true;
        }
        @Override public boolean isTerminal() { return cancelled; }
        @Override public boolean isSafeToRelease() { return cancelled; }
        @Override public MutationTerminalState terminalState() {
            return MutationTerminalState.CANCELLED;
        }
        @Override public void close() { cancel(); }
    }

    private static final class CloseTrackingMutation implements DimensionMutation {
        private int closeCalls;

        @Override public void advance(long deadlineNanos) { }
        @Override public boolean isTerminal() { return false; }
        @Override public boolean isSafeToRelease() { return false; }
        @Override public void close() { closeCalls++; }
    }

    private static final class ThrowingMutation implements DimensionMutation {
        private final MutationTerminalState failureState;

        private ThrowingMutation(MutationTerminalState failureState) {
            this.failureState = failureState;
        }

        @Override public void advance(long deadlineNanos) throws IOException {
            throw new IOException("advance failed");
        }

        @Override public boolean isTerminal() { return false; }
        @Override public boolean isSafeToRelease() { return false; }
        @Override public MutationTerminalState unhandledFailureState() { return failureState; }
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
