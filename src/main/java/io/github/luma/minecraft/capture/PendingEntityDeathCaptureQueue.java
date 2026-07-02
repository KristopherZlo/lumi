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

    void drain(MinecraftServer server, EntityMutationTracker.EntityDeathBatchRecorder recorder) {
        if (server == null || recorder == null) {
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            this.drain(level, recorder);
        }
    }

    private void drain(ServerLevel level, EntityMutationTracker.EntityDeathBatchRecorder recorder) {
        List<PendingDeathBatch> batches = this.pending(level);
        if (batches.isEmpty()) {
            return;
        }

        for (PendingDeathBatch batch : batches) {
            recorder.record(level, batch.oldPayloads(), batch.frame());
            this.remove(level, batch.frame().actionId());
        }
    }

    private synchronized List<PendingDeathBatch> pending(ServerLevel level) {
        LinkedHashMap<String, PendingDeathBatch> worldCaptures = this.pendingCaptures.get(level);
        if (worldCaptures == null || worldCaptures.isEmpty()) {
            return List.of();
        }
        return List.copyOf(worldCaptures.values());
    }

    private synchronized void remove(ServerLevel level, String actionId) {
        LinkedHashMap<String, PendingDeathBatch> worldCaptures = this.pendingCaptures.get(level);
        if (worldCaptures == null) {
            return;
        }
        worldCaptures.remove(actionId);
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

        private List<EntityPayload> oldPayloads() {
            return List.copyOf(this.oldPayloads.values());
        }
    }
}
