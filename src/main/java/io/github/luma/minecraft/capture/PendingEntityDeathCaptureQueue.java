package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Defers causal mob death replay so mass deaths collapse into one tick-time drain.
 */
final class PendingEntityDeathCaptureQueue {

    private final Map<ServerLevel, LinkedHashMap<String, PendingDeathBatch>> pendingCaptures =
            new IdentityHashMap<>();

    synchronized void enqueue(
            ServerLevel level,
            EntityPayload oldPayload,
            EntityMutationTracker.CaptureFrame frame
    ) {
        if (level == null
                || oldPayload == null
                || frame == null
                || oldPayload.entityId().isBlank()
                || frame.actionId() == null
                || frame.actionId().isBlank()) {
            return;
        }

        this.pendingCaptures
                .computeIfAbsent(level, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(frame.actionId(), ignored -> new PendingDeathBatch(frame))
                .add(oldPayload);
    }

    void drain(
            MinecraftServer server,
            EntityMutationTracker.EntityDeathBatchRecorder recorder,
            int maxCaptures
    ) {
        if (server == null || recorder == null || maxCaptures <= 0) {
            return;
        }

        int remaining = maxCaptures;
        for (ServerLevel level : server.getAllLevels()) {
            remaining -= this.drain(level, recorder, remaining);
            if (remaining <= 0) {
                break;
            }
        }
    }

    private int drain(
            ServerLevel level,
            EntityMutationTracker.EntityDeathBatchRecorder recorder,
            int maxCaptures
    ) {
        int processed = 0;
        while (processed < maxCaptures) {
            PendingDeathSlice slice = this.pending(level, maxCaptures - processed);
            if (slice == null) {
                break;
            }
            recorder.record(level, slice.oldPayloads(), slice.frame());
            this.remove(level, slice);
            processed += slice.oldPayloads().size();
        }
        return processed;
    }

    private synchronized PendingDeathSlice pending(ServerLevel level, int limit) {
        LinkedHashMap<String, PendingDeathBatch> worldCaptures = this.pendingCaptures.get(level);
        if (worldCaptures == null || worldCaptures.isEmpty()) {
            return null;
        }
        Map.Entry<String, PendingDeathBatch> entry = worldCaptures.entrySet().iterator().next();
        return new PendingDeathSlice(
                entry.getKey(),
                entry.getValue().frame(),
                entry.getValue().oldPayloads(limit)
        );
    }

    private synchronized void remove(ServerLevel level, PendingDeathSlice slice) {
        LinkedHashMap<String, PendingDeathBatch> worldCaptures = this.pendingCaptures.get(level);
        if (worldCaptures == null) {
            return;
        }
        PendingDeathBatch batch = worldCaptures.get(slice.actionId());
        if (batch == null) {
            return;
        }
        batch.remove(slice.oldPayloads());
        if (batch.isEmpty()) {
            worldCaptures.remove(slice.actionId());
        }
        if (worldCaptures.isEmpty()) {
            this.pendingCaptures.remove(level);
        }
    }

    private static final class PendingDeathBatch {

        private final EntityMutationTracker.CaptureFrame frame;
        private final LinkedHashMap<String, EntityPayload> oldPayloads = new LinkedHashMap<>();

        private PendingDeathBatch(EntityMutationTracker.CaptureFrame frame) {
            this.frame = frame;
        }

        private void add(EntityPayload oldPayload) {
            this.oldPayloads.putIfAbsent(oldPayload.entityId(), oldPayload);
        }

        private EntityMutationTracker.CaptureFrame frame() {
            return this.frame;
        }

        private List<EntityPayload> oldPayloads(int limit) {
            return this.oldPayloads.values().stream().limit(limit).toList();
        }

        private void remove(List<EntityPayload> payloads) {
            payloads.forEach(payload -> this.oldPayloads.remove(payload.entityId()));
        }

        private boolean isEmpty() {
            return this.oldPayloads.isEmpty();
        }
    }

    private record PendingDeathSlice(
            String actionId,
            EntityMutationTracker.CaptureFrame frame,
            List<EntityPayload> oldPayloads
    ) {
    }
}
