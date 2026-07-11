package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.integration.common.ExternalToolMutationOriginDetector;
import io.github.luma.integration.common.ObservedExternalToolOperation;
import java.time.Instant;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

public final class EntityMutationTracker {

    private static final int MAX_SPAWN_CAPTURES_PER_TICK = 128;
    private static final EntitySnapshotService SNAPSHOT_SERVICE = new EntitySnapshotService();
    private static final EntityMutationCapturePolicy CAPTURE_POLICY = new EntityMutationCapturePolicy();
    private static final EntitySpawnCaptureQueue SPAWN_CAPTURE_QUEUE = new EntitySpawnCaptureQueue(SNAPSHOT_SERVICE);
    private static final ExternalToolMutationOriginDetector TOOL_DETECTOR = ExternalToolMutationOriginDetector.getInstance();
    private EntityMutationTracker() {
    }

    public static PendingEntityMutation captureBefore(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return PendingEntityMutation.empty();
        }
        if (WorldMutationContext.captureSuppressed()) {
            return PendingEntityMutation.empty();
        }

        String entityType = entityType(entity);
        WorldMutationSource source = WorldMutationContext.currentSource();
        Instant actionStartedAt = EntityCausalContextRegistry.currentStartedAt().orElse(null);
        ObservedExternalToolOperation operation = null;
        if (!CAPTURE_POLICY.shouldInspectMutation(source, entityType)
                && !shouldInspectUndoRedo(source, entityType)) {
            if (WorldMutationContext.captureSuppressed()) {
                return PendingEntityMutation.empty();
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
        if (!CAPTURE_POLICY.shouldInspectMutation(source, entityType)
                && !shouldInspectUndoRedo(source, entityType)) {
            return PendingEntityMutation.empty();
        }

        return new PendingEntityMutation(
                level,
                SNAPSHOT_SERVICE.capture(level, entity),
                operation,
                actionStartedAt
        );
    }

    public static void captureAfter(Entity entity, PendingEntityMutation pending) {
        if (pending == null || pending.isEmpty() || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        EntityPayload newPayload = entity.isRemoved() ? null : SNAPSHOT_SERVICE.capture(level, entity);
        record(level, pending.oldPayload(), newPayload, pending.operation(), pending.actionStartedAt());
    }

    public static void captureSpawn(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return;
        }
        if (WorldMutationContext.captureSuppressed()) {
            return;
        }
        String entityType = entityType(entity);
        WorldMutationSource source = WorldMutationContext.currentSource();
        ObservedExternalToolOperation operation = null;
        if (!CAPTURE_POLICY.shouldInspectSpawnMutation(source, entityType)
                && !shouldInspectUndoRedo(source, entityType)) {
            if (WorldMutationContext.captureSuppressed()) {
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
        SPAWN_CAPTURE_QUEUE.enqueue(level, entity, CaptureFrame.current(operation));
    }

    private static boolean shouldInspectUndoRedo(WorldMutationSource source, String entityType) {
        String actionId = WorldMutationContext.currentActionId();
        return actionId != null
                && !actionId.isBlank()
                && CAPTURE_POLICY.shouldInspectUndoRedo(source, entityType);
    }

    public static void tick(MinecraftServer server) {
        SPAWN_CAPTURE_QUEUE.drain(
                server,
                EntityMutationTracker::record,
                false,
                MAX_SPAWN_CAPTURES_PER_TICK
        );
    }

    public static void drainPendingSpawns(MinecraftServer server) {
        SPAWN_CAPTURE_QUEUE.drain(server, EntityMutationTracker::record, true, Integer.MAX_VALUE);
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
            Instant actionStartedAt
    ) {
        if (oldPayload == null && newPayload == null) {
            return;
        }
        if (operation == null) {
            if (actionStartedAt == null) {
                HistoryCaptureManager.getInstance().recordEntityChange(level, oldPayload, newPayload);
            } else {
                recordDelayed(level, oldPayload, newPayload, actionStartedAt);
            }
            return;
        }

        boolean accessAllowed = operation.accessAllowed() || !level.getServer().isDedicatedServer();
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushExternalSource(
                operation.source(),
                operation.actor(),
                operation.actionId(),
                accessAllowed
        )) {
            if (actionStartedAt == null) {
                HistoryCaptureManager.getInstance().recordEntityChange(level, oldPayload, newPayload);
            } else {
                recordDelayed(level, oldPayload, newPayload, actionStartedAt);
            }
        }
    }

    private static void record(
            ServerLevel level,
            EntityPayload oldPayload,
            EntityPayload newPayload,
            CaptureFrame frame
    ) {
        if (frame == null || oldPayload == null && newPayload == null) {
            return;
        }

        if (frame.operation() != null) {
            boolean accessAllowed = frame.operation().accessAllowed() || !level.getServer().isDedicatedServer();
            try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushExternalSource(
                    frame.operation().source(),
                    frame.operation().actor(),
                    frame.operation().actionId(),
                    accessAllowed
            )) {
                recordDelayed(level, oldPayload, newPayload, frame.recordedAt());
            }
            return;
        }

        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(
                frame.source(),
                frame.actor(),
                frame.actionId(),
                frame.accessAllowed()
        )) {
            recordDelayed(level, oldPayload, newPayload, frame.recordedAt());
        }
    }

    private static void recordDelayed(
            ServerLevel level,
            EntityPayload oldPayload,
            EntityPayload newPayload,
            Instant actionStartedAt
    ) {
        if (oldPayload == null && newPayload == null) {
            return;
        }
        HistoryCaptureManager.getInstance().recordDelayedEntityChange(level, oldPayload, newPayload, actionStartedAt);
    }

    record CaptureFrame(
            WorldMutationSource source,
            String actor,
            String actionId,
            boolean accessAllowed,
            ObservedExternalToolOperation operation,
            Instant recordedAt
    ) {

        static CaptureFrame current(ObservedExternalToolOperation operation) {
            if (operation != null) {
                return new CaptureFrame(
                        operation.source(),
                        operation.actor(),
                        operation.actionId(),
                        operation.accessAllowed(),
                        operation,
                        EntityCausalContextRegistry.currentStartedAt().orElseGet(Instant::now)
                );
            }
            return new CaptureFrame(
                    WorldMutationContext.currentSource(),
                    WorldMutationContext.currentActor(),
                    WorldMutationContext.currentActionId(),
                    WorldMutationContext.currentAccessAllowed(),
                    null,
                    EntityCausalContextRegistry.currentStartedAt().orElseGet(Instant::now)
            );
        }
    }

    interface EntityChangeRecorder {

        void record(
                ServerLevel level,
                EntityPayload oldPayload,
                EntityPayload newPayload,
                CaptureFrame frame
        );
    }

    public record PendingEntityMutation(
            ServerLevel level,
            EntityPayload oldPayload,
            ObservedExternalToolOperation operation,
            Instant actionStartedAt
    ) {

        public static PendingEntityMutation empty() {
            return new PendingEntityMutation(null, null, null, null);
        }

        public boolean isEmpty() {
            return this.level == null || this.oldPayload == null;
        }

    }
}
