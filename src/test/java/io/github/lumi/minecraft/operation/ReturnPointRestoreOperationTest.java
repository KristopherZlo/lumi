package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.CapturedWorldState;
import io.github.lumi.domain.service.SaveRequest;
import io.github.lumi.domain.service.SaveResult;
import io.github.lumi.minecraft.world.DimensionFreeze;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class ReturnPointRestoreOperationTest {
    @Test
    void keepsOneFreezeFromReturnCaptureThroughPreparedRestore() throws Exception {
        ManualExecutor saveWriter = new ManualExecutor();
        WorkingIndexSnapshot clean = new WorkingIndexSnapshot(Map.of());
        SaveResult returnPoint = new SaveResult(
                id('2'), new BranchRef(new BranchName("main"), id('2'), 1), clean);
        SaveCaptureOperation save = new SaveCaptureOperation(
                request(), clean,
                dirty -> immediateCapture(new CapturedWorldState(
                        Map.of(), Map.of(), clean,
                        new io.github.lumi.domain.model.CommitStatistics(0, 0, 0, 0))),
                (request, captured) -> returnPoint,
                ignored -> { }, saveWriter);
        CompletableFuture<DimensionMutation> prepared = new CompletableFuture<>();
        ReturnPointRestoreOperation operation = new ReturnPointRestoreOperation(
                save, ignored -> prepared);
        RecordingFreeze freeze = new RecordingFreeze();
        DimensionOperationCoordinator coordinator = new DimensionOperationCoordinator(
                freeze, () -> 0L, 1L);
        coordinator.start(operation);

        coordinator.tick();
        assertEquals(SaveOperationStatus.WRITING, save.status());
        assertEquals(1, freeze.acquireCalls);
        assertEquals(0, freeze.releaseCalls);

        saveWriter.runNext();
        coordinator.tick();
        assertEquals(0, freeze.releaseCalls);
        assertTrue(coordinator.hasActiveOperation());

        prepared.complete(new CompleteMutation());
        coordinator.tick();
        assertEquals(1, freeze.releaseCalls);
        assertTrue(!coordinator.hasActiveOperation());
    }

    @Test
    void reportsFailedRestorePreparation() throws Exception {
        WorkingIndexSnapshot clean = new WorkingIndexSnapshot(Map.of());
        SaveResult returnPoint = new SaveResult(
                id('2'), new BranchRef(new BranchName("main"), id('2'), 1), clean);
        SaveCaptureOperation save = new SaveCaptureOperation(
                request(), clean,
                dirty -> immediateCapture(new CapturedWorldState(
                        Map.of(), Map.of(), clean,
                        new io.github.lumi.domain.model.CommitStatistics(0, 0, 0, 0))),
                (request, captured) -> returnPoint, ignored -> { }, Runnable::run);
        ReturnPointRestoreOperation operation = new ReturnPointRestoreOperation(
                save, ignored -> CompletableFuture.failedFuture(
                        new java.io.IOException("broken target")));

        operation.advance(Long.MAX_VALUE);
        operation.advance(Long.MAX_VALUE);
        operation.advance(Long.MAX_VALUE);

        assertEquals(MutationTerminalState.FAILED, operation.terminalState());
        assertEquals("broken target", operation.failure().orElseThrow().getMessage());
    }

    @Test
    void exposesPreparedRestoreFailure() throws Exception {
        WorkingIndexSnapshot clean = WorkingIndexSnapshot.empty();
        SaveResult returnPoint = new SaveResult(
                id('2'), new BranchRef(new BranchName("main"), id('2'), 1), clean);
        SaveCaptureOperation save = new SaveCaptureOperation(
                request(), clean,
                dirty -> immediateCapture(new CapturedWorldState(
                        Map.of(), Map.of(), clean,
                        new io.github.lumi.domain.model.CommitStatistics(0, 0, 0, 0))),
                (request, captured) -> returnPoint, ignored -> { }, Runnable::run);
        IOException failure = new IOException("restore mismatch");
        DimensionMutation restore = new CompleteMutation() {
            @Override public Optional<Throwable> failure() { return Optional.of(failure); }
        };
        ReturnPointRestoreOperation operation = new ReturnPointRestoreOperation(
                save, ignored -> CompletableFuture.completedFuture(restore));

        operation.advance(Long.MAX_VALUE);
        operation.advance(Long.MAX_VALUE);
        operation.advance(Long.MAX_VALUE);

        assertEquals(failure, operation.failure().orElseThrow());
    }

    @Test
    void cancelsNestedReturnPointCaptureBeforePublication() throws Exception {
        WorkingIndexSnapshot clean = WorkingIndexSnapshot.empty();
        int[] closes = {0};
        SaveCaptureOperation save = new SaveCaptureOperation(
                request(), clean,
                dirty -> new io.github.lumi.minecraft.world.WorldStateCapture.CaptureSession() {
                    @Override public boolean captureUntil(long deadlineNanos) { return false; }
                    @Override public CapturedWorldState finish() {
                        throw new AssertionError("Cancelled capture must not finish");
                    }
                    @Override public void close() { closes[0]++; }
                },
                (request, captured) -> {
                    throw new AssertionError("Cancelled Save must not publish");
                },
                ignored -> { }, Runnable::run);
        ReturnPointRestoreOperation operation = new ReturnPointRestoreOperation(
                save, ignored -> {
                    throw new AssertionError("Cancelled return point must not prepare Restore");
                });
        operation.advance(Long.MAX_VALUE);

        assertTrue(operation.cancel());

        assertEquals(1, closes[0]);
        assertEquals(MutationTerminalState.CANCELLED, operation.terminalState());
        assertTrue(operation.isSafeToRelease());
    }

    private static io.github.lumi.minecraft.world.WorldStateCapture.CaptureSession immediateCapture(
            CapturedWorldState captured) {
        return new io.github.lumi.minecraft.world.WorldStateCapture.CaptureSession() {
            @Override public boolean captureUntil(long deadlineNanos) { return true; }
            @Override public CapturedWorldState finish() { return captured; }
        };
    }

    private static SaveRequest request() {
        return new SaveRequest(
                new BranchRef(new BranchName("main"), id('1'), 0),
                new CommitAuthor(UUID.randomUUID(), "Builder"), "Return", Instant.EPOCH,
                UUID.randomUUID(), Optional.empty(), CommitKind.HIDDEN_RETURN);
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }

    private static class CompleteMutation implements DimensionMutation {
        private boolean complete;
        @Override public void advance(long deadlineNanos) { complete = true; }
        @Override public boolean isTerminal() { return complete; }
        @Override public boolean isSafeToRelease() { return complete; }
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        private void runNext() { tasks.remove().run(); }
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
