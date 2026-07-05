package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.debug.LumaLoadLog;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;

/**
 * Carries the builder action that primed TNT across the delayed fuse tick.
 */
public final class ExplosiveEntityContextRegistry {

    private static final ExplosiveEntityContextRegistry INSTANCE = new ExplosiveEntityContextRegistry();

    private final Map<UUID, ExplosiveContext> contexts = new HashMap<>();

    ExplosiveEntityContextRegistry() {
    }

    public static ExplosiveEntityContextRegistry getInstance() {
        return INSTANCE;
    }

    public void rememberSpawn(Entity entity) {
        ExplosiveContext.captureCurrent().ifPresent(context -> this.remember(entity, context));
    }

    public void rememberSpawn(Entity entity, ServerLevel level) {
        if (!(entity instanceof PrimedTnt)) {
            return;
        }
        Optional<ExplosiveContext> current = ExplosiveContext.captureCurrent();
        if (current.isPresent()) {
            this.remember(entity, current.get());
            this.logSpawnContext("current", entity, level, current.get());
            return;
        }
        Optional<ExplosiveContext> deferred = ExplosiveContext.captureDeferred(
                HistoryCaptureManager.getInstance().deferredActionContextNear(level, entity.blockPosition())
        );
        deferred.ifPresent(context -> {
            this.remember(entity, context);
            this.logSpawnContext("deferred", entity, level, context);
        });
        if (deferred.isEmpty()) {
            this.logSpawnContext("missing", entity, level, null);
        }
    }

    public Optional<ExplosiveContext> contextFor(Entity entity) {
        if (!(entity instanceof PrimedTnt)) {
            return Optional.empty();
        }
        Optional<ExplosiveContext> carrierContext = this.carrierContext(entity);
        if (carrierContext.isPresent()) {
            return carrierContext;
        }
        this.pruneExpiredContexts();
        synchronized (this.contexts) {
            return Optional.ofNullable(this.contexts.get(entity.getUUID()));
        }
    }

    public boolean pushContext(Entity entity) {
        Optional<ExplosiveContext> context = this.contextFor(entity);
        if (context.isEmpty()) {
            return false;
        }
        context.get().push();
        return true;
    }

    public boolean hasActiveContexts() {
        return this.activeContextCount() > 0;
    }

    public boolean hasActiveContextForAction(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return false;
        }
        this.pruneExpiredContexts();
        synchronized (this.contexts) {
            return this.contexts.values().stream()
                    .anyMatch(context -> actionId.equals(context.actionId()));
        }
    }

    public int activeContextCount() {
        this.pruneExpiredContexts();
        synchronized (this.contexts) {
            return this.contexts.size();
        }
    }

    public void forget(Entity entity) {
        if (entity == null) {
            return;
        }
        this.forget(entity.getUUID());
    }

    void forget(UUID entityId) {
        if (entityId == null) {
            return;
        }
        synchronized (this.contexts) {
            this.contexts.remove(entityId);
        }
        LumaLoadLog.event("tnt-context", "forget", "uuid=" + entityId);
    }

    void remember(Entity entity, ExplosiveContext context) {
        if (!(entity instanceof PrimedTnt) || context == null) {
            return;
        }
        this.rememberCarrier(entity, context);
        this.remember(entity.getUUID(), context);
    }

    void remember(UUID entityId, ExplosiveContext context) {
        if (entityId == null || context == null) {
            return;
        }
        synchronized (this.contexts) {
            this.contexts.put(entityId, context);
        }
        LumaLoadLog.event("tnt-context", "remember",
                "uuid=" + entityId
                        + ", action=" + context.actionId()
                        + ", actor=" + context.actor()
                        + ", source=" + context.source());
    }

    private void logSpawnContext(String origin, Entity entity, ServerLevel level, ExplosiveContext context) {
        LumaLoadLog.event("tnt-context", "spawn-context",
                "origin=" + origin
                        + ", uuid=" + entity.getUUID()
                        + ", time=" + (level == null ? -1 : level.getGameTime())
                        + ", pos=" + entity.blockPosition().getX()
                        + "," + entity.blockPosition().getY()
                        + "," + entity.blockPosition().getZ()
                        + ", action=" + (context == null ? "<none>" : context.actionId())
                        + ", actor=" + (context == null ? "<none>" : context.actor())
                        + ", source=" + (context == null ? "<none>" : context.source()));
    }

    private Optional<ExplosiveContext> carrierContext(Entity entity) {
        if (!(entity instanceof DeferredWorldMutationContextAccess access)) {
            return Optional.empty();
        }
        DeferredWorldMutationContext context = access.luma$deferredMutationContext();
        if (context == null || !context.hasAction()) {
            return Optional.empty();
        }
        return Optional.of(ExplosiveContext.fromDeferred(context));
    }

    private void rememberCarrier(Entity entity, ExplosiveContext context) {
        if (!(entity instanceof DeferredWorldMutationContextAccess access)) {
            return;
        }
        DeferredWorldMutationContext existing = access.luma$deferredMutationContext();
        if (existing != null && existing.hasAction()) {
            return;
        }
        access.luma$setDeferredMutationContext(context.toDeferred());
    }

    private void pruneExpiredContexts() {
        synchronized (this.contexts) {
            Iterator<Map.Entry<UUID, ExplosiveContext>> iterator = this.contexts.entrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue().expired()) {
                    iterator.remove();
                }
            }
        }
    }

    public record ExplosiveContext(
            WorldMutationSource source,
            String actor,
            String actionId,
            boolean accessAllowed,
            long createdAtMillis
    ) {

        static Optional<ExplosiveContext> captureCurrent() {
            String actionId = WorldMutationContext.currentActionId();
            if (actionId == null || actionId.isBlank()) {
                return Optional.empty();
            }
            WorldMutationSource source = WorldMutationContext.currentSource();
            if (!HistoryCaptureManager.shouldCaptureMutation(source)) {
                return Optional.empty();
            }
            return Optional.of(new ExplosiveContext(
                    WorldMutationSource.EXPLOSIVE,
                    WorldMutationContext.currentActor(),
                    actionId,
                    WorldMutationContext.currentAccessAllowed(),
                    System.currentTimeMillis()
            ));
        }

        static Optional<ExplosiveContext> captureDeferred(CaptureSessionState.DeferredActionContext context) {
            if (context == null || !context.hasAction()) {
                return Optional.empty();
            }
            return Optional.of(new ExplosiveContext(
                    WorldMutationSource.EXPLOSIVE,
                    context.actor(),
                    context.actionId(),
                    context.accessAllowed(),
                    System.currentTimeMillis()
            ));
        }

        static ExplosiveContext fromDeferred(DeferredWorldMutationContext context) {
            return new ExplosiveContext(
                    WorldMutationSource.EXPLOSIVE,
                    context.actor(),
                    context.actionId(),
                    context.accessAllowed(),
                    System.currentTimeMillis()
            );
        }

        DeferredWorldMutationContext toDeferred() {
            return new DeferredWorldMutationContext(
                    this.source,
                    this.actor,
                    this.actionId,
                    this.accessAllowed,
                    0
            );
        }

        void push() {
            WorldMutationContext.pushSource(this.source, this.actor, this.actionId, this.accessAllowed);
        }

        boolean expired() {
            return System.currentTimeMillis() - this.createdAtMillis > 120_000L;
        }
    }
}
