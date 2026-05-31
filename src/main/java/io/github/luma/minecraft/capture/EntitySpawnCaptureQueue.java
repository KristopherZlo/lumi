package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Captures spawn payloads after Minecraft has accepted the entity into the world.
 */
final class EntitySpawnCaptureQueue {

    private static final int MAX_CAPTURE_AGE_TICKS = 20;

    private final EntitySnapshotService snapshotService;
    private final Map<ServerLevel, LinkedHashMap<UUID, PendingSpawnCapture>> pendingCaptures =
            new IdentityHashMap<>();

    EntitySpawnCaptureQueue(EntitySnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    synchronized void enqueue(ServerLevel level, Entity entity, EntityMutationTracker.CaptureFrame frame, boolean undoOnly) {
        if (level == null || entity == null || frame == null || entity.getUUID() == null) {
            return;
        }
        EntityPayload initialPayload = this.snapshotService.capture(level, entity);
        if (initialPayload == null) {
            return;
        }

        this.pendingCaptures
                .computeIfAbsent(level, ignored -> new LinkedHashMap<>())
                .put(entity.getUUID(), new PendingSpawnCapture(
                        entity.getUUID(),
                        entity,
                        initialPayload,
                        frame,
                        undoOnly,
                        level.getGameTime()
                ));
    }

    void drain(
            MinecraftServer server,
            EntityMutationTracker.EntityChangeRecorder recorder,
            boolean allowInitialPayloadFallback
    ) {
        if (server == null || recorder == null) {
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            this.drain(level, recorder, allowInitialPayloadFallback);
        }
    }

    private void drain(
            ServerLevel level,
            EntityMutationTracker.EntityChangeRecorder recorder,
            boolean allowInitialPayloadFallback
    ) {
        List<PendingSpawnCapture> captures = this.pending(level);
        if (captures.isEmpty()) {
            return;
        }

        long now = level.getGameTime();
        for (PendingSpawnCapture capture : captures) {
            EntityPayload payload = this.resolvePayload(level, capture, allowInitialPayloadFallback);
            if (payload == null) {
                this.removeIfExpired(level, capture, now);
                continue;
            }

            recorder.record(level, null, payload, capture.frame(), capture.undoOnly());
            this.remove(level, capture.entityId());
        }
    }

    private EntityPayload resolvePayload(
            ServerLevel level,
            PendingSpawnCapture capture,
            boolean allowInitialPayloadFallback
    ) {
        Entity entity = level.getEntity(capture.entityId());
        if ((entity == null || entity.isRemoved()) && allowInitialPayloadFallback) {
            entity = capture.acceptedEntity();
        }
        if (entity == null || entity.isRemoved()) {
            return allowInitialPayloadFallback ? capture.initialPayload() : null;
        }

        EntityPayload currentPayload = this.snapshotService.capture(level, entity);
        if (currentPayload != null) {
            return currentPayload;
        }
        return allowInitialPayloadFallback ? capture.initialPayload() : null;
    }

    private synchronized List<PendingSpawnCapture> pending(ServerLevel level) {
        LinkedHashMap<UUID, PendingSpawnCapture> worldCaptures = this.pendingCaptures.get(level);
        if (worldCaptures == null || worldCaptures.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new ArrayList<>(worldCaptures.values()));
    }

    private void removeIfExpired(ServerLevel level, PendingSpawnCapture capture, long now) {
        if (now - capture.queuedAtGameTime() >= MAX_CAPTURE_AGE_TICKS) {
            this.remove(level, capture.entityId());
        }
    }

    private synchronized void remove(ServerLevel level, UUID entityId) {
        LinkedHashMap<UUID, PendingSpawnCapture> worldCaptures = this.pendingCaptures.get(level);
        if (worldCaptures == null) {
            return;
        }
        worldCaptures.remove(entityId);
        if (worldCaptures.isEmpty()) {
            this.pendingCaptures.remove(level);
        }
    }

    private record PendingSpawnCapture(
            UUID entityId,
            Entity acceptedEntity,
            EntityPayload initialPayload,
            EntityMutationTracker.CaptureFrame frame,
            boolean undoOnly,
            long queuedAtGameTime
    ) {
    }
}
