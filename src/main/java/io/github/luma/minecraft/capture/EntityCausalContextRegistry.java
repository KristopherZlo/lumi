package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
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
 * Carries a short-lived player action identity from damage time to delayed
 * death, loot, and removal callbacks.
 */
public final class EntityCausalContextRegistry {

    private static final int CONTEXT_TTL_TICKS = 100;
    private static final EntityCausalContextRegistry INSTANCE = new EntityCausalContextRegistry();
    private static final ThreadLocal<Deque<Instant>> ACTIVE_STARTED_AT =
            ThreadLocal.withInitial(ArrayDeque::new);

    private final EntitySnapshotService snapshotService = new EntitySnapshotService();
    private final Map<UUID, EntityCausalContext> contexts = new HashMap<>();

    private EntityCausalContextRegistry() {
    }

    public static EntityCausalContextRegistry getInstance() {
        return INSTANCE;
    }

    public synchronized boolean rememberCurrentPlayerAction(Entity entity, ServerLevel level) {
        if (!this.canRemember(entity, level)) {
            return false;
        }

        EntityPayload originalPayload = this.snapshotService.capture(level, entity);
        if (originalPayload == null) {
            return false;
        }

        this.removeExpired(level.getGameTime());
        this.contexts.put(entity.getUUID(), new EntityCausalContext(
                WorldMutationContext.currentSource(),
                WorldMutationContext.currentActor(),
                WorldMutationContext.currentActionId(),
                WorldMutationContext.currentAccessAllowed(),
                Instant.now(),
                level.getGameTime() + CONTEXT_TTL_TICKS,
                originalPayload
        ));
        return true;
    }

    public synchronized boolean rememberCurrentPlayerActionIfAbsent(Entity entity, ServerLevel level) {
        EntityCausalContext context = this.context(entity, level);
        if (context != null && context.source() == WorldMutationContext.currentSource()) {
            return false;
        }
        return this.rememberCurrentPlayerAction(entity, level);
    }

    public ContextFrame pushIfPresent(Entity entity, ServerLevel level) {
        return this.pushIfPresent(entity, level, null);
    }

    public ContextFrame pushIfPresent(Entity entity, ServerLevel level, WorldMutationSource sourceOverride) {
        EntityCausalContext context = this.context(entity, level);
        if (context == null || this.currentFrameAlreadyHasAction()) {
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

    public synchronized Optional<EntityPayload> oldPayloadOverride(Entity entity, ServerLevel level) {
        EntityCausalContext context = this.context(entity, level);
        if (context == null || context.oldPayload() == null) {
            return Optional.empty();
        }
        return Optional.of(new EntityPayload(context.oldPayload().copyTag()));
    }

    public static Optional<Instant> currentStartedAt() {
        Deque<Instant> frames = ACTIVE_STARTED_AT.get();
        return frames.isEmpty() ? Optional.empty() : Optional.of(frames.peek());
    }

    public synchronized void clear(Entity entity) {
        if (entity != null && entity.getUUID() != null) {
            this.contexts.remove(entity.getUUID());
        }
    }

    synchronized void clearForTests() {
        this.contexts.clear();
        ACTIVE_STARTED_AT.remove();
    }

    private boolean canRemember(Entity entity, ServerLevel level) {
        if (entity == null || level == null || entity instanceof ServerPlayer || entity.getUUID() == null) {
            return false;
        }
        if (WorldMutationContext.captureSuppressed()) {
            return false;
        }
        return this.canRememberSource(WorldMutationContext.currentSource(), WorldMutationContext.currentActionId());
    }

    boolean canRememberSource(WorldMutationSource source, String actionId) {
        return (source == WorldMutationSource.PLAYER
                || source == WorldMutationSource.MOB
                || source == WorldMutationSource.EXPLOSIVE)
                && actionId != null
                && !actionId.isBlank();
    }

    private synchronized EntityCausalContext context(Entity entity, ServerLevel level) {
        if (entity == null || entity.getUUID() == null || level == null) {
            return null;
        }
        this.removeExpired(level.getGameTime());
        return this.contexts.get(entity.getUUID());
    }

    private boolean currentFrameAlreadyHasAction() {
        String actionId = WorldMutationContext.currentActionId();
        return actionId != null && !actionId.isBlank();
    }

    private void removeExpired(long gameTime) {
        Iterator<Map.Entry<UUID, EntityCausalContext>> iterator = this.contexts.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAtGameTime() < gameTime) {
                iterator.remove();
            }
        }
    }

    private record EntityCausalContext(
            WorldMutationSource source,
            String actor,
            String actionId,
            boolean accessAllowed,
            Instant startedAt,
            long expiresAtGameTime,
            EntityPayload oldPayload
    ) {
    }

    public static final class ContextFrame implements AutoCloseable {

        private final WorldMutationContext.SourceFrame sourceFrame;
        private final boolean pushedStartedAt;
        private boolean closed;

        private ContextFrame(WorldMutationContext.SourceFrame sourceFrame, boolean pushedStartedAt) {
            this.sourceFrame = sourceFrame;
            this.pushedStartedAt = pushedStartedAt;
        }

        private static ContextFrame empty() {
            return new ContextFrame(null, false);
        }

        public boolean active() {
            return this.sourceFrame != null;
        }

        @Override
        public void close() {
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
