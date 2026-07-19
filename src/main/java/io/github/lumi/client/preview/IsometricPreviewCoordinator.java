package io.github.lumi.client.preview;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.OperationEventPayload;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
    private final PreviewRenderMeshBuilder meshes =
            new PreviewRenderMeshBuilder();
    private final TexturedPreviewCaptureService capture =
            new TexturedPreviewCaptureService();
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
        if (!enabled || snapshot.pendingBounds().isEmpty()) return;
        discard(requestId);
        pending.put(requestId, new Pending(
                snapshot.dimensionId(), snapshot.head(),
                snapshot.pendingBounds().orElseThrow(),
                null, null, null, null));
        while (pending.size() > MAX_PENDING) {
            Iterator<UUID> iterator = pending.keySet().iterator();
            UUID oldest = iterator.next();
            iterator.remove();
            closeCapture(oldest, null);
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
                startBuild(event.requestId(), item, event.head());
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
                    pending.put(requestId, withMesh.withCapture(
                            capture.capture(
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
                    closeCapture(requestId, item);
                    store.save(item.dimensionId(), target, image);
                    return;
                }
            } catch (RuntimeException failed) {
                pending.remove(requestId);
                closeCapture(requestId, item);
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
        Minecraft client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        var level = client.level;
        if (server == null || level == null) {
            discard(requestId);
            return;
        }
        CompletableFuture<PreviewRenderMesh> build =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return snapshots.read(
                                server, level, item.dimensionId(),
                                target, item.bounds());
                    } catch (Exception failed) {
                        throw new CompletionException(failed);
                    }
                }, worker).thenCompose(frozen ->
                        meshes.scheduleBuild(frozen, item.bounds(), worker));
        pending.put(requestId, item.withTarget(target, build));
    }

    private void discard(UUID requestId) {
        Pending removed = pending.remove(requestId);
        closeCapture(requestId, removed);
    }

    private void closeCapture(UUID ignored, Pending item) {
        if (item == null) return;
        if (item.build() != null && !item.build().isDone()) {
            item.build().cancel(true);
        }
        if (item.offscreen() != null) {
            item.offscreen().renderTarget().destroyBuffers();
            if (!item.offscreen().imageFuture().isDone()) {
                item.offscreen().imageFuture().whenComplete((image, failed) -> {
                    if (image != null) image.close();
                });
            }
        }
        if (item.mesh() != null) item.mesh().close();
    }

    @Override
    public synchronized void close() {
        clear();
        capture.close();
        worker.shutdownNow();
    }

    private record Pending(
            String dimensionId,
            CommitId before,
            BlockBox bounds,
            CommitId target,
            CompletableFuture<PreviewRenderMesh> build,
            PreviewRenderMesh mesh,
            TexturedPreviewCaptureService.PendingPreviewCapture offscreen) {
        private Pending withTarget(
                CommitId value, CompletableFuture<PreviewRenderMesh> future) {
            return new Pending(
                    dimensionId, before, bounds, value,
                    future, mesh, offscreen);
        }

        private Pending withMesh(PreviewRenderMesh value) {
            return new Pending(
                    dimensionId, before, bounds, target,
                    build, value, offscreen);
        }

        private Pending withCapture(
                TexturedPreviewCaptureService.PendingPreviewCapture value) {
            return new Pending(
                    dimensionId, before, bounds, target,
                    build, mesh, value);
        }
    }
}
