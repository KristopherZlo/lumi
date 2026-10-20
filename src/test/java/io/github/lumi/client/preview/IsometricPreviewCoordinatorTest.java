package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.OperationEventPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IsometricPreviewCoordinatorTest {
    @Test
    void startsOnlyAfterSuccessAndKeepsTheQueueBounded() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/preview/IsometricPreviewCoordinator.java"));

        assertTrue(source.contains("MAX_PENDING = 4"));
        assertTrue(source.contains("discard(pending.keySet().iterator().next())"));
        assertTrue(source.contains("case SUCCEEDED"));
        assertTrue(source.contains("event.previewBounds()"));
        assertTrue(source.contains(".or(item::previewBounds)"));
        assertTrue(source.contains("boundsLimiter.limit("));
        assertTrue(source.contains("snapshots.read("));
        assertTrue(source.contains("meshes.scheduleBuild("));
        assertTrue(source.contains("capture().capture("));
        assertTrue(source.contains("if (capture == null)"));
        assertTrue(source.contains("if (capture != null) capture.close()"));
        assertTrue(source.contains("store.save(item.dimensionId(), target, image)"));
        assertTrue(source.contains("worker.shutdown()"));
    }

    @Test
    void unavailableIntegratedCaptureClearsTheLoadingFlag() {
        var store = new ClientVersionPreviewStore();
        try (var coordinator = new IsometricPreviewCoordinator(store,
                (dimension, commit, bounds) -> Optional.empty())) {
            UUID request = UUID.randomUUID();
            CommitId target = commit(1);
            coordinator.request(request, snapshot());
            coordinator.accept(success(request, target));
            assertFalse(store.isLoading("minecraft:overworld", target));
        }
    }

    @Test
    void evictionClearsLoadingAndCancelsTheEvictedBuild() {
        var store = new ClientVersionPreviewStore();
        var futures = new ArrayList<CompletableFuture<PreviewRenderMesh>>();
        try (var coordinator = new IsometricPreviewCoordinator(store,
                (dimension, commit, bounds) -> {
                    var future = new CompletableFuture<PreviewRenderMesh>();
                    futures.add(future);
                    return Optional.of(future);
                })) {
            for (int index = 1; index <= IsometricPreviewCoordinator.MAX_PENDING + 1; index++) {
                UUID request = UUID.randomUUID();
                coordinator.request(request, snapshot());
                coordinator.accept(success(request, commit(index)));
            }
            assertFalse(store.isLoading("minecraft:overworld", commit(1)));
            assertTrue(futures.getFirst().isCancelled());
            assertTrue(store.isLoading("minecraft:overworld", commit(2)));
            coordinator.clear();
            assertTrue(futures.stream().allMatch(CompletableFuture::isCancelled));
            assertFalse(store.isLoading("minecraft:overworld", commit(2)));
        }
    }

    @Test
    void rejectedBuildAndAsynchronousFailureClearLoading() {
        var store = new ClientVersionPreviewStore();
        try (var rejected = new IsometricPreviewCoordinator(store,
                (dimension, commit, bounds) -> {
                    throw new java.util.concurrent.RejectedExecutionException("worker closed");
                })) {
            UUID request = UUID.randomUUID();
            rejected.request(request, snapshot());
            rejected.accept(success(request, commit(1)));
            assertFalse(store.isLoading("minecraft:overworld", commit(1)));
        }
        try (var failed = new IsometricPreviewCoordinator(store,
                (dimension, commit, bounds) -> Optional.of(
                        CompletableFuture.failedFuture(new IllegalStateException("mesh"))))) {
            UUID request = UUID.randomUUID();
            failed.request(request, snapshot());
            failed.accept(success(request, commit(2)));
            failed.tick();
            assertFalse(store.isLoading("minecraft:overworld", commit(2)));
        }
    }

    @Test
    void discardedBuildClosesCompletedAndLateResults() {
        var closes = new java.util.concurrent.atomic.AtomicInteger();
        var store = new ClientVersionPreviewStore();
        try (var coordinator = new IsometricPreviewCoordinator(store,
                (dimension, commit, bounds) -> Optional.of(CompletableFuture.completedFuture(mesh(closes))))) {
            UUID request = UUID.randomUUID();
            coordinator.request(request, snapshot());
            coordinator.accept(success(request, commit(1)));
            coordinator.clear();
            coordinator.clear();
        }
        assertTrue(closes.get() == 1);
        var late = new CompletableFuture<PreviewRenderMesh>() {
            @Override public boolean cancel(boolean interrupt) { return false; }
        };
        try (var coordinator = new IsometricPreviewCoordinator(store,
                (dimension, commit, bounds) -> Optional.of(late))) {
            UUID request = UUID.randomUUID();
            coordinator.request(request, snapshot());
            coordinator.accept(success(request, commit(2)));
            coordinator.clear();
            late.complete(mesh(closes));
            coordinator.clear();
        }
        assertTrue(closes.get() == 2);
        var cancelled = new CompletableFuture<Object>();
        IsometricPreviewCoordinator.discardBuild(cancelled, ignored -> closes.incrementAndGet());
        assertTrue(cancelled.isCancelled());
        assertTrue(closes.get() == 2);
    }

    @Test
    void captureFailureClosesTheAcquiredMeshOnce() {
        var closes = new java.util.concurrent.atomic.AtomicInteger();
        var store = new ClientVersionPreviewStore();
        try (var coordinator = new IsometricPreviewCoordinator(store,
                (dimension, commit, bounds) -> Optional.of(CompletableFuture.completedFuture(mesh(closes))))) {
            UUID request = UUID.randomUUID();
            coordinator.request(request, snapshot());
            coordinator.accept(success(request, commit(1)));
            // No render device exists in this unit test, so offscreen capture must fail.
            coordinator.tick();
            assertFalse(store.isLoading("minecraft:overworld", commit(1)));
            assertTrue(closes.get() == 1);
            coordinator.clear();
        }
        assertTrue(closes.get() == 1);
    }

    private static PreviewRenderMesh mesh(java.util.concurrent.atomic.AtomicInteger closes) {
        var buffers = new net.minecraft.client.renderer.SectionBufferBuilderPack() {
            @Override public void close() { super.close(); closes.incrementAndGet(); }
        };
        return new PreviewRenderMesh(buffers, new java.util.EnumMap<>(net.minecraft.client.renderer.chunk.ChunkSectionLayer.class));
    }

    private static CommitId commit(int value) {
        return CommitId.hash(new byte[] {(byte) value});
    }

    private static HistorySnapshotPayload snapshot() {
        return new HistorySnapshotPayload("minecraft:overworld", commit(0), 0, 1, 0,
                List.of(), Optional.of(new BlockBox(0, 0, 0, 15, 15, 15)), false, false,
                new UUID(0, 1), "Default", "main", List.of(), List.of(), List.of(),
                List.of(), List.of());
    }

    private static OperationEventPayload success(UUID request, CommitId target) {
        return new OperationEventPayload(request, "minecraft:overworld",
                OperationEventPayload.State.SUCCEEDED, "saved", target, 1);
    }
}
