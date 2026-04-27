package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import io.github.luma.integration.common.ExternalToolMutationOriginDetector;
import io.github.luma.integration.common.ObservedExternalToolOperation;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

public final class EntityMutationTracker {

    private static final EntitySnapshotService SNAPSHOT_SERVICE = new EntitySnapshotService();
    private static final ExternalToolMutationOriginDetector TOOL_DETECTOR = ExternalToolMutationOriginDetector.getInstance();

    private EntityMutationTracker() {
    }

    public static PendingEntityMutation captureBefore(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return PendingEntityMutation.empty();
        }

        ObservedExternalToolOperation operation = null;
        if (!HistoryCaptureManager.shouldCaptureMutation(WorldMutationContext.currentSource())) {
            Optional<ObservedExternalToolOperation> detected = TOOL_DETECTOR.detectOperation();
            if (detected.isEmpty()) {
                return PendingEntityMutation.empty();
            }
            operation = detected.get();
        }

        return new PendingEntityMutation(level, SNAPSHOT_SERVICE.capture(level, entity), operation);
    }

    public static void captureAfter(Entity entity, PendingEntityMutation pending) {
        if (pending == null || pending.isEmpty() || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        EntityPayload newPayload = entity.isRemoved() ? null : SNAPSHOT_SERVICE.capture(level, entity);
        record(level, pending.oldPayload(), newPayload, pending.operation());
    }

    public static void captureSpawn(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return;
        }
        ObservedExternalToolOperation operation = operationForCurrentContext();
        EntityPayload newPayload = SNAPSHOT_SERVICE.capture(level, entity);
        record(level, null, newPayload, operation);
    }

    public static PendingEntityMutation captureRemoval(Entity entity) {
        return captureBefore(entity);
    }

    private static ObservedExternalToolOperation operationForCurrentContext() {
        if (HistoryCaptureManager.shouldCaptureMutation(WorldMutationContext.currentSource())) {
            return null;
        }
        return TOOL_DETECTOR.detectOperation().orElse(null);
    }

    private static void record(
            ServerLevel level,
            EntityPayload oldPayload,
            EntityPayload newPayload,
            ObservedExternalToolOperation operation
    ) {
        if (oldPayload == null && newPayload == null) {
            return;
        }
        if (operation == null) {
            HistoryCaptureManager.getInstance().recordEntityChange(level, oldPayload, newPayload);
            return;
        }

        WorldMutationContext.pushExternalSource(operation.source(), operation.actor(), operation.actionId());
        try {
            HistoryCaptureManager.getInstance().recordEntityChange(level, oldPayload, newPayload);
        } finally {
            WorldMutationContext.popSource();
        }
    }

    public record PendingEntityMutation(
            ServerLevel level,
            EntityPayload oldPayload,
            ObservedExternalToolOperation operation
    ) {

        public static PendingEntityMutation empty() {
            return new PendingEntityMutation(null, null, null);
        }

        public boolean isEmpty() {
            return this.level == null || this.oldPayload == null;
        }
    }
}
