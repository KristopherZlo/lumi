package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Carries short-lived mutation attribution from damage time to delayed
 * death, loot, and removal callbacks.
 */
public final class EntityCausalContextRegistry {

    private static final int CONTEXT_TTL_TICKS = 100;
    private static final EntityCausalContextRegistry INSTANCE = new EntityCausalContextRegistry();
    private static final ThreadLocal<Deque<Instant>> ACTIVE_STARTED_AT =
            ThreadLocal.withInitial(ArrayDeque::new);

    private final Map<EntityContextKey, EntityCausalContext> contexts = new HashMap<>();

    private EntityCausalContextRegistry() {
    }

    public static EntityCausalContextRegistry getInstance() {
        return INSTANCE;
    }

    public synchronized boolean rememberCurrentMutation(Entity entity, ServerLevel level) {
        if (!this.canRemember(entity, level)) {
            return false;
        }

        this.removeExpired(level.getGameTime());
        this.contexts.put(this.key(entity, level), new EntityCausalContext(
                WorldMutationContext.currentSource(),
                WorldMutationContext.currentActor(),
                WorldMutationContext.currentActionId(),
                WorldMutationContext.currentAccessAllowed(),
                Instant.now(),
                level.getGameTime() + CONTEXT_TTL_TICKS
        ));
        return true;
    }

    public synchronized boolean rememberCurrentMutationIfAbsent(Entity entity, ServerLevel level) {
        EntityCausalContext context = this.context(entity, level);
        if (context != null) {
            return false;
        }
        return this.rememberCurrentMutation(entity, level);
    }

    public ContextFrame pushIfPresent(Entity entity, ServerLevel level) {
        return this.pushIfPresent(entity, level, null);
    }

    public ContextFrame pushIfPresent(Entity entity, ServerLevel level, WorldMutationSource sourceOverride) {
        EntityCausalContext context = this.context(entity, level);
        if (context == null) {
            return ContextFrame.empty();
        }

        WorldMutationContext.SourceFrame sourceFrame = WorldMutationContext.pushSource(
                sourceOverride == null ? context.source() : sourceOverride,
                context.actor(),
                context.actionId(),
                context.accessAllowed()
        );
        ACTIVE_STARTED_AT.get().push(context.startedAt());
        return new ContextFrame(sourceFrame, true);
    }

    public boolean hasContext(Entity entity, ServerLevel level) {
        return this.context(entity, level) != null;
    }

    public static Optional<Instant> currentStartedAt() {
        Deque<Instant> frames = ACTIVE_STARTED_AT.get();
        return frames.isEmpty() ? Optional.empty() : Optional.of(frames.peek());
    }

    public synchronized void clear(Entity entity) {
        if (entity != null && entity.getUUID() != null) {
            this.contexts.keySet().removeIf(key -> entity.getUUID().equals(key.entityUuid()));
        }
    }

    private boolean canRemember(Entity entity, ServerLevel level) {
        if (entity == null || level == null || entity instanceof ServerPlayer || entity.getUUID() == null) {
            return false;
        }
        if (WorldMutationContext.captureSuppressed()) {
            return false;
        }
        return this.canRememberSource(WorldMutationContext.currentSource());
    }

    boolean canRememberSource(WorldMutationSource source) {
        return HistoryCaptureManager.shouldCaptureMutation(source);
    }

    private synchronized EntityCausalContext context(Entity entity, ServerLevel level) {
        if (entity == null || entity.getUUID() == null || level == null) {
            return null;
        }
        if (this.contexts.isEmpty()) {
            return null;
        }
        EntityContextKey key = this.key(entity, level);
        EntityCausalContext context = this.contexts.get(key);
        if (context != null && context.expiresAtGameTime() < level.getGameTime()) {
            this.contexts.remove(key);
            return null;
        }
        return context;
    }

    private void removeExpired(long gameTime) {
        Iterator<Map.Entry<EntityContextKey, EntityCausalContext>> iterator = this.contexts.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAtGameTime() < gameTime) {
                iterator.remove();
            }
        }
    }

    private EntityContextKey key(Entity entity, ServerLevel level) {
        return new EntityContextKey(level.dimension().identifier().toString(), entity.getUUID());
    }

    private record EntityContextKey(String dimensionId, UUID entityUuid) {
    }

    private record EntityCausalContext(
            WorldMutationSource source,
            String actor,
            String actionId,
            boolean accessAllowed,
            Instant startedAt,
            long expiresAtGameTime
    ) {
    }

    public static final class ContextFrame implements AutoCloseable {

        private static final ContextFrame EMPTY = new ContextFrame(null, false);

        private final WorldMutationContext.SourceFrame sourceFrame;
        private final boolean pushedStartedAt;
        private boolean closed;

        private ContextFrame(WorldMutationContext.SourceFrame sourceFrame, boolean pushedStartedAt) {
            this.sourceFrame = sourceFrame;
            this.pushedStartedAt = pushedStartedAt;
        }

        private static ContextFrame empty() {
            return EMPTY;
        }

        public boolean active() {
            return this.sourceFrame != null;
        }

        @Override
        public void close() {
            if (this.sourceFrame == null) {
                return;
            }
            if (this.closed) {
                return;
            }
            this.closed = true;
            if (this.pushedStartedAt) {
                Deque<Instant> frames = ACTIVE_STARTED_AT.get();
                if (!frames.isEmpty()) {
                    frames.pop();
                }
                if (frames.isEmpty()) {
                    ACTIVE_STARTED_AT.remove();
                }
            }
            if (this.sourceFrame != null) {
                this.sourceFrame.close();
            }
        }
    }
}
