package io.github.lumi.client.preview;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.OperationEventPayload;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;

/** Bounded Save-event coordinator for immutable isometric commit previews. */
final class IsometricPreviewCoordinator implements AutoCloseable {
    static final int MAX_PENDING = 4;
    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();
    private final ClientVersionPreviewStore store;
    private final CommitPreviewSnapshotReader snapshots =
            new CommitPreviewSnapshotReader();
    private PreviewRenderMeshBuilder meshes;
    private final PreviewBuild builds;
    private final PreviewBoundsLimiter boundsLimiter =
            new PreviewBoundsLimiter();
    private TexturedPreviewCaptureService capture;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "Lumi-Isometric-Preview-" + THREAD_NUMBER.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Math.max(
                        Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                return thread;
            });
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();

    IsometricPreviewCoordinator(ClientVersionPreviewStore store) {
        this.store = Objects.requireNonNull(store, "store");
        builds = this::buildMesh;
    }

    IsometricPreviewCoordinator(ClientVersionPreviewStore store, PreviewBuild builds) {
        this.store = Objects.requireNonNull(store, "store");
        this.builds = Objects.requireNonNull(builds, "builds");
    }

    synchronized void request(
            UUID requestId, HistorySnapshotPayload snapshot) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(snapshot, "snapshot");
        boolean enabled = snapshot.workspaces().stream()
                .filter(HistorySnapshotPayload.WorkspaceView::active)
                .findFirst()
                .map(HistorySnapshotPayload.WorkspaceView::previewGenerationEnabled)
                .orElse(true);
        if (!enabled) return;
        discard(requestId);
        pending.put(requestId, new Pending(
                snapshot.dimensionId(), snapshot.head(),
                snapshot.pendingBounds(),
                null, null, null, null));
        while (pending.size() > MAX_PENDING) {
            discard(pending.keySet().iterator().next());
        }
    }

    synchronized void accept(OperationEventPayload event) {
        Objects.requireNonNull(event, "event");
        Pending item = pending.get(event.requestId());
        if (item == null) return;
        switch (event.state()) {
            case ACCEPTED, PROGRESS -> { return; }
            case SUCCEEDED -> {
                if (event.head().equals(item.before())) {
                    discard(event.requestId());
                    return;
                }
                Optional<BlockBox> bounds = event.previewBounds()
                        .or(item::previewBounds);
                if (bounds.isEmpty()) {
                    discard(event.requestId());
                    return;
                }
                store.beginLoading(item.dimensionId(), event.head());
                startBuild(
                        event.requestId(), item.withBounds(
                                boundsLimiter.limit(bounds.orElseThrow())),
                        event.head());
            }
            case FAILED, CANCELLED, RETURNED, DEGRADED ->
                    discard(event.requestId());
        }
    }

    synchronized void tick() {
        for (var entry : pending.entrySet()) {
            UUID requestId = entry.getKey();
            Pending item = entry.getValue();
            try {
                if (item.build() != null && item.build().isDone()
                        && item.mesh() == null) {
                    PreviewRenderMesh mesh = item.build().join();
                    Pending withMesh = item.withMesh(mesh);
                    pending.put(requestId, withMesh);
                    pending.put(requestId, withMesh.withCapture(
                            capture().capture(
                                    Minecraft.getInstance(), item.bounds(),
                                    mesh, worker)));
                    return;
                }
                if (item.offscreen() != null
                        && item.offscreen().imageFuture().isDone()) {
                    NativeImage image = item.offscreen().imageFuture().join();
                    CommitId target = Objects.requireNonNull(
                            item.target(), "Preview target");
                    pending.remove(requestId);
                    closeCapture(item, false);
                    store.save(item.dimensionId(), target, image);
                    return;
                }
            } catch (RuntimeException failed) {
                closeCapture(requestId, pending.remove(requestId));
                if (item.target() != null) {
                    store.failLoading(item.dimensionId(), item.target());
                }
                LumiMod.LOGGER.warn(
                        "Failed to render immutable Lumi preview", failed);
                return;
            }
        }
    }

    synchronized void clear() {
        pending.forEach(this::closeCapture);
        pending.clear();
        store.releaseAll();
    }

    private void startBuild(
            UUID requestId, Pending item, CommitId target) {
        pending.put(requestId, item.withTarget(target, null));
        try {
            var build = builds.prepare(item.dimensionId(), target, item.bounds());
            if (build.isEmpty()) {
                discard(requestId);
            } else {
                pending.put(requestId, item.withTarget(target, build.orElseThrow()));
            }
        } catch (RuntimeException failed) {
            discard(requestId);
            LumiMod.LOGGER.warn("Failed to prepare immutable Lumi preview", failed);
        }
    }

    private Optional<CompletableFuture<PreviewRenderMesh>> buildMesh(
            String dimensionId, CommitId target, BlockBox bounds) {
        Minecraft client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        var level = client.level;
        if (server == null || level == null) {
            return Optional.empty();
        }
        if (meshes == null) meshes = new PreviewRenderMeshBuilder();
        CompletableFuture<PreviewRenderMesh> build = new CompletableFuture<>();
        CompletableFuture.supplyAsync(() -> {
                    try {
                        return snapshots.read(
                                server, level, dimensionId, target, bounds);
                    } catch (Exception failed) {
                        throw new CompletionException(failed);
                    }
                }, worker).whenComplete((frozen, failed) -> {
                    if (failed != null) { build.completeExceptionally(failed); return; }
                    if (build.isCancelled()) return;
                    try {
                        var mesh = meshes.scheduleBuild(frozen, bounds, worker);
                        build.whenComplete((ignored, error) -> { if (build.isCancelled()) mesh.cancel(true); });
                        mesh.whenComplete((value, error) -> {
                            if (error != null) build.completeExceptionally(error);
                            else if (!build.complete(value)) value.close();
                        });
                    } catch (RuntimeException rejected) { build.completeExceptionally(rejected); }
                });
        return Optional.of(build);
    }

    @FunctionalInterface
    interface PreviewBuild {
        Optional<CompletableFuture<PreviewRenderMesh>> prepare(
                String dimensionId, CommitId target, BlockBox bounds);
    }

    private void discard(UUID requestId) {
        Pending removed = pending.remove(requestId);
        closeCapture(requestId, removed);
        if (removed != null && removed.target() != null) {
            store.failLoading(removed.dimensionId(), removed.target());
        }
    }

    private TexturedPreviewCaptureService capture() {
        if (capture == null) capture = new TexturedPreviewCaptureService();
        return capture;
    }

    private void closeCapture(UUID ignored, Pending item) {
        closeCapture(item, true);
    }

    private void closeCapture(Pending item, boolean discardImage) {
        if (item == null) return;
        if (item.mesh() == null && item.build() != null) discardBuild(item.build(), PreviewRenderMesh::close);
        if (item.offscreen() != null) {
            item.offscreen().renderTarget().destroyBuffers();
            if (discardImage) {
                item.offscreen().imageFuture().whenComplete((image, failed) -> {
                    if (image != null) image.close();
                });
            }
        }
        if (item.mesh() != null) item.mesh().close();
    }

    static <T> void discardBuild(CompletableFuture<T> build, java.util.function.Consumer<T> dispose) {
        build.whenComplete((value, failed) -> { if (value != null) dispose.accept(value); });
        build.cancel(true);
    }

    @Override
    public synchronized void close() {
        clear();
        if (capture != null) capture.close();
        worker.shutdown();
    }

    private record Pending(
            String dimensionId,
            CommitId before,
            Optional<BlockBox> previewBounds,
            CommitId target,
            CompletableFuture<PreviewRenderMesh> build,
            PreviewRenderMesh mesh,
            TexturedPreviewCaptureService.PendingPreviewCapture offscreen) {
        private BlockBox bounds() {
            return previewBounds.orElseThrow();
        }

        private Pending withBounds(BlockBox value) {
            return new Pending(
                    dimensionId, before, Optional.of(value), target,
                    build, mesh, offscreen);
        }

        private Pending withTarget(
                CommitId value, CompletableFuture<PreviewRenderMesh> future) {
            return new Pending(
                    dimensionId, before, previewBounds, value,
                    future, mesh, offscreen);
        }

        private Pending withMesh(PreviewRenderMesh value) {
            return new Pending(
                    dimensionId, before, previewBounds, target,
                    build, value, offscreen);
        }

        private Pending withCapture(
                TexturedPreviewCaptureService.PendingPreviewCapture value) {
            return new Pending(
                    dimensionId, before, previewBounds, target,
                    build, mesh, value);
        }
    }
}
