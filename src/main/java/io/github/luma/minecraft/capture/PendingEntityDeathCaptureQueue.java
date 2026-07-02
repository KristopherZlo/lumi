package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import java.util.ArrayList;
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

    private final Map<ServerLevel, LinkedHashMap<String, PendingDeathCapture>> pendingCaptures =
            new IdentityHashMap<>();

    synchronized void enqueue(
            ServerLevel level,
            EntityPayload oldPayload,
            EntityMutationTracker.CaptureFrame frame,
            boolean undoOnly
    ) {
        if (level == null || oldPayload == null || frame == null || oldPayload.entityId().isBlank()) {
            return;
        }

        this.pendingCaptures
                .computeIfAbsent(level, ignored -> new LinkedHashMap<>())
                .putIfAbsent(oldPayload.entityId(), new PendingDeathCapture(oldPayload, frame, undoOnly));
    }

    void drain(MinecraftServer server, EntityMutationTracker.EntityChangeRecorder recorder) {
        if (server == null || recorder == null) {
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            this.drain(level, recorder);
        }
    }

    private void drain(ServerLevel level, EntityMutationTracker.EntityChangeRecorder recorder) {
        List<PendingDeathCapture> captures = this.pending(level);
        if (captures.isEmpty()) {
            return;
        }

        for (PendingDeathCapture capture : captures) {
            recorder.record(level, capture.oldPayload(), null, capture.frame(), capture.undoOnly());
            this.remove(level, capture.oldPayload().entityId());
        }
    }

    private synchronized List<PendingDeathCapture> pending(ServerLevel level) {
        LinkedHashMap<String, PendingDeathCapture> worldCaptures = this.pendingCaptures.get(level);
        if (worldCaptures == null || worldCaptures.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new ArrayList<>(worldCaptures.values()));
    }

    private synchronized void remove(ServerLevel level, String entityId) {
        LinkedHashMap<String, PendingDeathCapture> worldCaptures = this.pendingCaptures.get(level);
        if (worldCaptures == null) {
            return;
        }
        worldCaptures.remove(entityId);
        if (worldCaptures.isEmpty()) {
            this.pendingCaptures.remove(level);
        }
    }

    private record PendingDeathCapture(
            EntityPayload oldPayload,
            EntityMutationTracker.CaptureFrame frame,
            boolean undoOnly
    ) {
    }
}
