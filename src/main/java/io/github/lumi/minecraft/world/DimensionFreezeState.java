package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import java.util.Objects;
import java.util.Set;

/** Per-dimension freeze flag with a thread-scoped bypass for verified Lumi apply. */
public final class DimensionFreezeState implements DimensionFreeze {
    private final ThreadLocal<Integer> authorizationDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Integer> entityAdditionDepth = ThreadLocal.withInitial(() -> 0);
    private Set<EntityChunkKey> suppressedEntityLoads;
    private boolean frozen;

    @Override
    public synchronized Lease acquire() {
        if (frozen) {
            throw new IllegalStateException("Dimension is already frozen");
        }
        frozen = true;
        return new ActiveLease();
    }

    public synchronized boolean isFrozen() {
        return frozen;
    }

    public synchronized Lease suppressEntityLoads(Set<EntityChunkKey> keys) {
        if (!frozen) {
            throw new IllegalStateException("Dimension must be frozen");
        }
        if (suppressedEntityLoads != null) {
            throw new IllegalStateException("Entity loads are already suppressed");
        }
        suppressedEntityLoads = Set.copyOf(keys);
        return new EntityLoadSuppressionLease();
    }

    public synchronized boolean suppressesEntityLoad(int chunkX, int chunkZ) {
        return suppressedEntityLoads != null
                && suppressedEntityLoads.contains(new EntityChunkKey(chunkX, chunkZ));
    }

    public boolean isMutationAllowed() {
        return !isFrozen() || authorizationDepth.get() > 0;
    }

    public boolean isAuthorizedMutation() {
        return authorizationDepth.get() > 0;
    }

    public boolean isEntityAdditionAllowed() {
        return !isFrozen() || entityAdditionDepth.get() > 0;
    }

    public void runAuthorized(Runnable mutation) {
        runScoped(authorizationDepth, mutation);
    }

    public void runAuthorizedEntityAddition(Runnable mutation) {
        runScoped(entityAdditionDepth, () -> runAuthorized(mutation));
    }

    private static void runScoped(ThreadLocal<Integer> depth, Runnable action) {
        Objects.requireNonNull(action, "action");
        int previous = depth.get();
        depth.set(previous + 1);
        try {
            action.run();
        } finally {
            if (previous == 0) {
                depth.remove();
            } else {
                depth.set(previous);
            }
        }
    }

    private final class ActiveLease implements Lease {
        private boolean released;

        @Override
        public void release() {
            synchronized (DimensionFreezeState.this) {
                if (released || !frozen) {
                    throw new IllegalStateException("Dimension freeze lease is not active");
                }
                if (suppressedEntityLoads != null) {
                    throw new IllegalStateException("Entity-load suppression is still active");
                }
                released = true;
                frozen = false;
            }
        }
    }

    private final class EntityLoadSuppressionLease implements Lease {
        private boolean released;

        @Override
        public void release() {
            synchronized (DimensionFreezeState.this) {
                if (released || !frozen || suppressedEntityLoads == null) {
                    throw new IllegalStateException(
                            "Entity-load suppression lease is not active");
                }
                released = true;
                suppressedEntityLoads = null;
            }
        }
    }
}
