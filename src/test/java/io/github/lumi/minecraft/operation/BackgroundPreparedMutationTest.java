package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.minecraft.world.DimensionFreeze;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BackgroundPreparedMutationTest {
    @Test
    void canHoldFreezeWhilePreparationIsPending() throws Exception {
        CompletableFuture<TestMutation> future = new CompletableFuture<>();
        BackgroundPreparedMutation<TestMutation> prepared = new BackgroundPreparedMutation<>(
                future, () -> { }, ignored -> { }, true);
        RecordingFreeze freeze = new RecordingFreeze();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L);
        coordinator.start(prepared);

        coordinator.tick();
        assertEquals(1, freeze.acquireCalls);
        assertEquals(0, freeze.releaseCalls);

        future.complete(new TestMutation());
        coordinator.tick();
        assertEquals(1, freeze.releaseCalls);
    }

    @Test
    void ownsPreparationThenValidatesUnderFreezeBeforeDelegating() throws Exception {
        CompletableFuture<TestMutation> future = new CompletableFuture<>();
        AtomicInteger validations = new AtomicInteger();
        AtomicInteger discards = new AtomicInteger();
        BackgroundPreparedMutation<TestMutation> prepared = new BackgroundPreparedMutation<>(
                future, validations::incrementAndGet, ignored -> discards.incrementAndGet());
        RecordingFreeze freeze = new RecordingFreeze();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L);
        coordinator.start(prepared);

        coordinator.tick();
        assertEquals(0, freeze.acquireCalls);

        TestMutation delegate = new TestMutation();
        future.complete(delegate);
        coordinator.tick();

        assertEquals(1, validations.get());
        assertEquals(1, delegate.advances);
        assertEquals(0, discards.get());
        assertEquals(1, freeze.acquireCalls);
        assertEquals(1, freeze.releaseCalls);
        assertTrue(!coordinator.hasActiveOperation());
    }

    @Test
    void discardsPreparedArtifactWhenFrozenValidationFails() throws Exception {
        TestMutation delegate = new TestMutation();
        AtomicInteger discards = new AtomicInteger();
        BackgroundPreparedMutation<TestMutation> prepared = new BackgroundPreparedMutation<>(
                CompletableFuture.completedFuture(delegate),
                () -> { throw new IOException("world changed"); },
                ignored -> discards.incrementAndGet());
        RecordingFreeze freeze = new RecordingFreeze();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L);
        coordinator.start(prepared);

        coordinator.tick();

        assertEquals(1, discards.get());
        assertTrue(prepared.failure().isPresent());
        assertEquals(MutationTerminalState.FAILED, prepared.terminalState());
        assertEquals(0, delegate.advances);
        assertEquals(1, freeze.releaseCalls);
        assertTrue(!coordinator.hasActiveOperation());
    }

    @Test
    void recoveryPreparationFailureDegradesAndRetainsFreeze() throws Exception {
        BackgroundPreparedMutation<TestMutation> prepared = new BackgroundPreparedMutation<>(
                CompletableFuture.failedFuture(new IOException("corrupt target")),
                () -> { }, ignored -> { }, true, true);
        RecordingFreeze freeze = new RecordingFreeze();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L);
        coordinator.start(prepared);

        coordinator.tick();

        assertEquals(MutationTerminalState.DEGRADED, prepared.terminalState());
        assertEquals(0, freeze.releaseCalls);
        assertTrue(coordinator.hasActiveOperation());
    }

    @Test
    void exposesPreparedDelegateFailure() throws Exception {
        IOException failure = new IOException("restore mismatch");
        DimensionMutation delegate = new DimensionMutation() {
            @Override public void advance(long deadlineNanos) { }
            @Override public boolean isTerminal() { return true; }
            @Override public boolean isSafeToRelease() { return false; }
            @Override public Optional<Throwable> failure() { return Optional.of(failure); }
        };
        BackgroundPreparedMutation<DimensionMutation> prepared =
                new BackgroundPreparedMutation<>(
                        CompletableFuture.completedFuture(delegate),
                        () -> { }, ignored -> { });

        prepared.advance(Long.MAX_VALUE);

        assertEquals(failure, prepared.failure().orElseThrow());
    }

    @Test
    void cancelsAndDiscardsCompletedPreparationBeforeActivation() throws Exception {
        TestMutation delegate = new TestMutation();
        AtomicInteger discards = new AtomicInteger();
        BackgroundPreparedMutation<TestMutation> prepared = new BackgroundPreparedMutation<>(
                CompletableFuture.completedFuture(delegate),
                () -> { }, ignored -> discards.incrementAndGet());

        assertTrue(prepared.cancel());

        assertEquals(1, discards.get());
        assertEquals(1, delegate.closeCalls);
        assertEquals(MutationTerminalState.CANCELLED, prepared.terminalState());
    }

    @Test
    void refusesCancellationOfRecoveryPreparation() throws Exception {
        BackgroundPreparedMutation<TestMutation> prepared = new BackgroundPreparedMutation<>(
                CompletableFuture.completedFuture(new TestMutation()),
                () -> { }, ignored -> { }, true, true);

        assertFalse(prepared.cancel());
    }

    private static final class TestMutation implements DimensionMutation {
        private int advances;
        private int closeCalls;
        @Override public void advance(long deadlineNanos) { advances++; }
        @Override public boolean isTerminal() { return advances == 1; }
        @Override public boolean isSafeToRelease() { return advances == 1; }
        @Override public void close() { closeCalls++; }
    }

    private static final class RecordingFreeze implements DimensionFreeze {
        private int acquireCalls;
        private int releaseCalls;
        @Override public Lease acquire() {
            acquireCalls++;
            return () -> releaseCalls++;
        }
    }
}
