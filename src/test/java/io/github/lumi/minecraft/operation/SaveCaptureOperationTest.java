package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndex;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.CapturedWorldState;
import io.github.lumi.domain.service.SavePublicationProgress;
import io.github.lumi.domain.service.SavePublisher;
import io.github.lumi.domain.service.SaveRequest;
import io.github.lumi.domain.service.SaveResult;
import io.github.lumi.minecraft.world.WorldStateCapture;
import io.github.lumi.minecraft.world.SavePreparation;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class SaveCaptureOperationTest {
    @Test
    void holdsFreezeUntilPreparationEstablishesDurableBoundary() throws Exception {
        WorkingIndex working = new WorkingIndex();
        SectionKey key = new SectionKey(1, 0, 1);
        working.markDirty(key);
        var dirty = working.snapshot();
        CapturedWorldState captured = new CapturedWorldState(
                Map.of(key, airSection()), Map.of(), dirty,
                new CommitStatistics(1, 0, SectionBlob.BLOCK_COUNT, 0));
        TwoStepPreparation preparation = new TwoStepPreparation(dirty);
        TwoStepCapture world = new TwoStepCapture(captured);
        SaveCaptureOperation operation = new SaveCaptureOperation(
                request(), preparation, world,
                (request, state) -> new SaveResult(id('2'),
                        new BranchRef(new BranchName("main"), id('2'), 1), state.generations()),
                generations -> { }, Runnable::run);

        operation.advance(50L);
        assertEquals(SaveOperationStatus.PREPARING, operation.status());
        assertTrue(!operation.isSafeToRelease());
        assertEquals(0, world.session.captureCalls);
        assertEquals(WorkingIndexSnapshot.empty(), operation.previewGenerations());

        operation.advance(100L);
        assertEquals(SaveOperationStatus.CAPTURING, operation.status());
        assertEquals(1, world.session.captureCalls);
        assertEquals(dirty, operation.previewGenerations());
    }

    @Test
    void releasesAfterCaptureAndClearsOnlySavedGenerationInBackground() throws Exception {
        SectionKey key = new SectionKey(0, 0, 0);
        WorkingIndex working = new WorkingIndex();
        working.markDirty(key);
        var dirty = working.snapshot();
        CapturedWorldState captured = new CapturedWorldState(
                Map.of(key, airSection()), Map.of(), dirty,
                new CommitStatistics(1, 0, 1, 0));
        TwoStepCapture world = new TwoStepCapture(captured);
        ManualExecutor background = new ManualExecutor();
        CommitId savedId = id('2');
        BranchRef published = new BranchRef(new BranchName("main"), savedId, 1);
        SavePublisher publisher = new SavePublisher() {
            @Override public SaveResult save(SaveRequest request, CapturedWorldState state) {
                return new SaveResult(savedId, published, state.generations());
            }

            @Override public SaveResult save(
                    SaveRequest request,
                    CapturedWorldState state,
                    java.util.function.Consumer<SavePublicationProgress> progress) {
                progress.accept(new SavePublicationProgress(
                        "Save: publishing object pack", 5, 8));
                return save(request, state);
            }
        };
        SaveCaptureOperation operation = new SaveCaptureOperation(
                request(), dirty, world, publisher,
                generations -> working.clearCaptured(generations), background);

        operation.advance(50L);
        assertEquals(SaveOperationStatus.CAPTURING, operation.status());
        assertTrue(!operation.isSafeToRelease());

        operation.advance(100L);
        assertEquals(SaveOperationStatus.WRITING, operation.status());
        assertTrue(operation.isSafeToRelease());
        assertTrue(!operation.isTerminal());

        working.markDirty(key);
        background.runNext();
        assertEquals(new OperationProgress(
                "Save: publishing object pack", 5, 8), operation.progress());
        operation.advance(150L);

        assertEquals(SaveOperationStatus.COMPLETE, operation.status());
        assertEquals(2L, working.snapshot().generations().get(key));
        assertEquals(savedId, operation.result().orElseThrow().commitId());
        assertEquals(2, world.session.captureCalls);
    }

    @Test
    void reportsFailedBackgroundPublication() throws Exception {
        WorkingIndexSnapshot clean = new WorkingIndexSnapshot(Map.of());
        CapturedWorldState captured = new CapturedWorldState(
                Map.of(), Map.of(), clean, new CommitStatistics(0, 0, 0, 0));
        SaveCaptureOperation operation = new SaveCaptureOperation(
                request(), clean, dirty -> new WorldStateCapture.CaptureSession() {
                    @Override public boolean captureUntil(long deadlineNanos) { return true; }
                    @Override public CapturedWorldState finish() { return captured; }
                },
                (request, state) -> { throw new java.io.IOException("disk full"); },
                ignored -> { }, Runnable::run);

        operation.advance(Long.MAX_VALUE);
        operation.advance(Long.MAX_VALUE);

        assertEquals(SaveOperationStatus.FAILED, operation.status());
        assertEquals(MutationTerminalState.FAILED, operation.terminalState());
        assertEquals("disk full", operation.failure().orElseThrow().getMessage());
    }

    @Test
    void cancelsFrozenCaptureAndClosesOwnedSessions() throws Exception {
        WorkingIndexSnapshot dirty = new WorkingIndexSnapshot(Map.of());
        CapturedWorldState captured = new CapturedWorldState(
                Map.of(), Map.of(), dirty, new CommitStatistics(0, 0, 0, 0));
        TwoStepPreparation preparation = new TwoStepPreparation(dirty);
        TwoStepCapture world = new TwoStepCapture(captured);
        SaveCaptureOperation operation = new SaveCaptureOperation(
                request(), preparation, world,
                (request, state) -> {
                    throw new AssertionError("Cancelled Save must not publish");
                },
                ignored -> { }, Runnable::run);

        operation.advance(50L);
        operation.advance(100L);
        assertEquals(SaveOperationStatus.CAPTURING, operation.status());

        assertTrue(operation.cancel());

        assertEquals(SaveOperationStatus.CANCELLED, operation.status());
        assertEquals(MutationTerminalState.CANCELLED, operation.terminalState());
        assertTrue(operation.isSafeToRelease());
        assertEquals(1, preparation.closeCalls);
        assertEquals(1, world.session.closeCalls);
    }

    private static SaveRequest request() {
        return new SaveRequest(
                new BranchRef(new BranchName("main"), id('1'), 0),
                new CommitAuthor(UUID.randomUUID(), "Builder"), "Save", Instant.EPOCH,
                UUID.randomUUID(), Optional.empty(), CommitKind.MANUAL);
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }

    private static SectionBlob airSection() {
        return new SectionBlob(
                new ArrayList<>(Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:air")), Map.of());
    }

    private static final class TwoStepCapture implements WorldStateCapture {
        private final Session session;

        private TwoStepCapture(CapturedWorldState captured) {
            session = new Session(captured);
        }

        @Override public CaptureSession begin(io.github.lumi.domain.model.WorkingIndexSnapshot dirty) {
            return session;
        }

        private static final class Session implements CaptureSession {
            private final CapturedWorldState captured;
            private int captureCalls;
            private int closeCalls;

            private Session(CapturedWorldState captured) { this.captured = captured; }
            @Override public boolean captureUntil(long deadlineNanos) { return ++captureCalls == 2; }
            @Override public CapturedWorldState finish() { return captured; }
            @Override public void close() { closeCalls++; }
        }
    }

    private static final class TwoStepPreparation implements SavePreparation {
        private final io.github.lumi.domain.model.WorkingIndexSnapshot dirty;
        private int calls;
        private int closeCalls;

        private TwoStepPreparation(io.github.lumi.domain.model.WorkingIndexSnapshot dirty) {
            this.dirty = dirty;
        }

        @Override public Session begin() {
            return new Session() {
                @Override public boolean prepareUntil(long deadlineNanos) { return ++calls == 2; }
                @Override public io.github.lumi.domain.model.WorkingIndexSnapshot finish() {
                    return dirty;
                }
                @Override public WorkingIndexSnapshot previewGenerations() {
                    return dirty;
                }
                @Override public void close() { closeCalls++; }
            };
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        private void runNext() { tasks.remove().run(); }
    }
}
