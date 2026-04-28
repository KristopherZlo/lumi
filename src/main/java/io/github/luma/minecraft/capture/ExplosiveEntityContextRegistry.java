package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;

/**
 * Carries the builder action that primed TNT across the delayed fuse tick.
 */
public final class ExplosiveEntityContextRegistry {

    private static final ExplosiveEntityContextRegistry INSTANCE = new ExplosiveEntityContextRegistry();

    private final Map<Entity, ExplosiveContext> contexts = Collections.synchronizedMap(new WeakHashMap<>());

    private ExplosiveEntityContextRegistry() {
    }

    public static ExplosiveEntityContextRegistry getInstance() {
        return INSTANCE;
    }

    public void rememberSpawn(Entity entity) {
        ExplosiveContext.captureCurrent().ifPresent(context -> this.remember(entity, context));
    }

    public Optional<ExplosiveContext> contextFor(Entity entity) {
        if (!(entity instanceof PrimedTnt)) {
            return Optional.empty();
        }
        synchronized (this.contexts) {
            return Optional.ofNullable(this.contexts.get(entity));
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
            this.contexts.remove(entity);
        }
    }

    void remember(Entity entity, ExplosiveContext context) {
        if (!(entity instanceof PrimedTnt) || context == null) {
            return;
        }
        synchronized (this.contexts) {
            this.contexts.put(entity, context);
        }
    }

    public record ExplosiveContext(
            WorldMutationSource source,
            String actor,
            String actionId,
            boolean accessAllowed
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
                    WorldMutationContext.currentAccessAllowed()
            ));
        }

        void push() {
            WorldMutationContext.pushSource(this.source, this.actor, this.actionId, this.accessAllowed);
        }
    }
}
