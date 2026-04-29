package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.integration.common.ExternalToolMutationOriginDetector;
import io.github.luma.integration.common.ObservedExternalToolOperation;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

public final class EntityMutationTracker {

    private static final EntitySnapshotService SNAPSHOT_SERVICE = new EntitySnapshotService();
    private static final EntityMutationCapturePolicy CAPTURE_POLICY = new EntityMutationCapturePolicy();
    private static final ExternalToolMutationOriginDetector TOOL_DETECTOR = ExternalToolMutationOriginDetector.getInstance();

    private EntityMutationTracker() {
    }

    public static PendingEntityMutation captureBefore(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return PendingEntityMutation.empty();
        }

        String entityType = entityType(entity);
        WorldMutationSource source = WorldMutationContext.currentSource();
        ObservedExternalToolOperation operation = null;
        if (!CAPTURE_POLICY.shouldInspectMutation(source, entityType)) {
            if (CAPTURE_POLICY.shouldInspectUndoOnlyMutation(source, entityType)) {
                return new PendingEntityMutation(level, SNAPSHOT_SERVICE.capture(level, entity), null, true);
            }
            if (!CAPTURE_POLICY.shouldInspectExternalToolFallback(entityType)) {
                return PendingEntityMutation.empty();
            }
            Optional<ObservedExternalToolOperation> detected = TOOL_DETECTOR.detectOperation();
            if (detected.isEmpty()) {
                return PendingEntityMutation.empty();
            }
            operation = detected.get();
            source = operation.source();
        }
        if (!CAPTURE_POLICY.shouldInspectMutation(source, entityType)) {
            return PendingEntityMutation.empty();
        }

        return new PendingEntityMutation(level, SNAPSHOT_SERVICE.capture(level, entity), operation, false);
    }

    public static void captureAfter(Entity entity, PendingEntityMutation pending) {
        if (pending == null || pending.isEmpty() || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        EntityPayload newPayload = entity.isRemoved() ? null : SNAPSHOT_SERVICE.capture(level, entity);
        record(level, pending.oldPayload(), newPayload, pending.operation(), pending.undoOnly());
    }

    public static void captureSpawn(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return;
        }
        String entityType = entityType(entity);
        WorldMutationSource source = WorldMutationContext.currentSource();
        ObservedExternalToolOperation operation = null;
        if (!CAPTURE_POLICY.shouldInspectMutation(source, entityType)) {
            if (CAPTURE_POLICY.shouldInspectUndoOnlyMutation(source, entityType)) {
                EntityPayload newPayload = SNAPSHOT_SERVICE.capture(level, entity);
                record(level, null, newPayload, null, true);
                return;
            }
            if (!CAPTURE_POLICY.shouldInspectExternalToolFallback(entityType)) {
                return;
            }
            Optional<ObservedExternalToolOperation> detected = TOOL_DETECTOR.detectOperation();
            if (detected.isEmpty()) {
                return;
            }
            operation = detected.get();
            source = operation.source();
        }
        EntityPayload newPayload = SNAPSHOT_SERVICE.capture(level, entity);
        record(level, null, newPayload, operation, false);
    }

    public static PendingEntityMutation captureRemoval(Entity entity) {
        return captureBefore(entity);
    }

    private static String entityType(Entity entity) {
        if (entity == null || entity.getType() == null) {
            return "";
        }
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static void record(
            ServerLevel level,
            EntityPayload oldPayload,
            EntityPayload newPayload,
            ObservedExternalToolOperation operation,
            boolean undoOnly
    ) {
        if (oldPayload == null && newPayload == null) {
            return;
        }
        if (operation == null) {
            if (undoOnly) {
                HistoryCaptureManager.getInstance().recordUndoOnlyEntityChange(level, oldPayload, newPayload);
            } else {
                HistoryCaptureManager.getInstance().recordEntityChange(level, oldPayload, newPayload);
            }
            return;
        }

        WorldMutationContext.pushExternalSource(operation.source(), operation.actor(), operation.actionId());
        try {
            if (undoOnly) {
                HistoryCaptureManager.getInstance().recordUndoOnlyEntityChange(level, oldPayload, newPayload);
            } else {
                HistoryCaptureManager.getInstance().recordEntityChange(level, oldPayload, newPayload);
            }
        } finally {
            WorldMutationContext.popSource();
        }
    }

    public record PendingEntityMutation(
            ServerLevel level,
            EntityPayload oldPayload,
            ObservedExternalToolOperation operation,
            boolean undoOnly
    ) {

        public static PendingEntityMutation empty() {
            return new PendingEntityMutation(null, null, null, false);
        }

        public boolean isEmpty() {
            return this.level == null || this.oldPayload == null;
        }
    }
}
