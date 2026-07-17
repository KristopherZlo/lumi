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
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Correlates one world-only framebuffer thumbnail with a successful Save intent. */
public final class ClientVersionPreviewCapture {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;
    private static final int MAX_PENDING = 4;
    private final ClientVersionPreviewStore store;
    private final Map<UUID, PendingCapture> pending = new LinkedHashMap<>();

    public ClientVersionPreviewCapture(ClientVersionPreviewStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public void register() {
        WorldRenderEvents.END_MAIN.register(context -> captureNext());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }

    public synchronized void request(
            UUID requestId, HistorySnapshotPayload snapshot) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(snapshot, "snapshot");
        PendingCapture replaced = pending.put(requestId, new PendingCapture(
                snapshot.dimensionId(), snapshot.head(), snapshot.pendingBounds(),
                false, null, null));
        close(replaced);
        while (pending.size() > MAX_PENDING) {
            Iterator<PendingCapture> iterator = pending.values().iterator();
            close(iterator.next());
            iterator.remove();
        }
    }

    public synchronized void accept(OperationEventPayload event) {
        Objects.requireNonNull(event, "event");
        PendingCapture capture = pending.get(event.requestId());
        if (capture == null) {
            return;
        }
        switch (event.state()) {
            case ACCEPTED, PROGRESS -> { return; }
            case SUCCEEDED -> {
                if (event.head().equals(capture.before())) {
                    discard(event.requestId());
                    return;
                }
                pending.put(event.requestId(), capture.withTarget(event.head()));
                complete(event.requestId());
            }
            case FAILED, CANCELLED, RETURNED, DEGRADED -> discard(event.requestId());
        }
    }

    public synchronized void clear() {
        pending.values().forEach(ClientVersionPreviewCapture::close);
        pending.clear();
        store.releaseAll();
    }

    private synchronized void captureNext() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        for (var entry : pending.entrySet()) {
            PendingCapture capture = entry.getValue();
            if (capture.image() != null || capture.capturing()) {
                continue;
            }
            UUID requestId = entry.getKey();
            pending.put(requestId, capture.withCapturing());
            try {
                Screenshot.takeScreenshot(
                        client.getMainRenderTarget(),
                        image -> acceptImage(requestId, image));
            } catch (RuntimeException failed) {
                pending.put(requestId, capture);
                LumiMod.LOGGER.warn("Failed to capture Lumi Save preview", failed);
            }
            return;
        }
    }

    private synchronized void acceptImage(UUID requestId, NativeImage source) {
        PendingCapture capture = pending.get(requestId);
        if (capture == null) {
            source.close();
            return;
        }
        NativeImage thumbnail = null;
        try (source) {
            int sourceWidth = source.getWidth();
            int sourceHeight = source.getHeight();
            int cropWidth = Math.min(sourceWidth, sourceHeight * 16 / 9);
            int cropHeight = Math.min(sourceHeight, sourceWidth * 9 / 16);
            thumbnail = new NativeImage(WIDTH, HEIGHT, false);
            source.resizeSubRectTo(
                    (sourceWidth - cropWidth) / 2,
                    (sourceHeight - cropHeight) / 2,
                    cropWidth, cropHeight, thumbnail);
            pending.put(requestId, capture.withImage(thumbnail));
            thumbnail = null;
            complete(requestId);
        } catch (RuntimeException failed) {
            discard(requestId);
            LumiMod.LOGGER.warn("Failed to resize Lumi Save preview", failed);
        } finally {
            if (thumbnail != null) thumbnail.close();
        }
    }

    private void complete(UUID requestId) {
        PendingCapture capture = pending.get(requestId);
        if (capture == null || capture.image() == null || capture.target() == null) {
            return;
        }
        pending.remove(requestId);
        store.save(capture.dimensionId(), capture.target(), capture.image());
    }

    private void discard(UUID requestId) {
        close(pending.remove(requestId));
    }

    private static void close(PendingCapture capture) {
        if (capture != null && capture.image() != null) capture.image().close();
    }

    private record PendingCapture(
            String dimensionId,
            CommitId before,
            Optional<BlockBox> bounds,
            boolean capturing,
            NativeImage image,
            CommitId target) {
        private PendingCapture withCapturing() {
            return new PendingCapture(
                    dimensionId, before, bounds, true, image, target);
        }

        private PendingCapture withImage(NativeImage value) {
            return new PendingCapture(
                    dimensionId, before, bounds, false, value, target);
        }

        private PendingCapture withTarget(CommitId value) {
            return new PendingCapture(
                    dimensionId, before, bounds, capturing, image, value);
        }
    }
}
