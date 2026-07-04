package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.WorldMutationSource;
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

    private ExplosiveEntityContextRegistry() {
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
            return;
        }
        ExplosiveContext.captureDeferred(
                HistoryCaptureManager.getInstance().deferredActionContextNear(level, entity.blockPosition())
        ).ifPresent(context -> this.remember(entity, context));
    }

    public Optional<ExplosiveContext> contextFor(Entity entity) {
        if (!(entity instanceof PrimedTnt)) {
            return Optional.empty();
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

    public void forget(Entity entity) {
        if (entity == null) {
            return;
        }
        synchronized (this.contexts) {
            this.contexts.remove(entity.getUUID());
        }
    }

    void remember(Entity entity, ExplosiveContext context) {
        if (!(entity instanceof PrimedTnt) || context == null) {
            return;
        }
        synchronized (this.contexts) {
            this.contexts.put(entity.getUUID(), context);
        }
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

        void push() {
            WorldMutationContext.pushSource(this.source, this.actor, this.actionId, this.accessAllowed);
        }

        boolean expired() {
            return System.currentTimeMillis() - this.createdAtMillis > 120_000L;
        }
    }
}
