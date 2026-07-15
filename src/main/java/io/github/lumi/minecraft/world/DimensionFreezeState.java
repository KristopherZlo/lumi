package io.github.lumi.minecraft.world;

import java.util.Objects;

/** Per-dimension freeze flag with a thread-scoped bypass for verified Lumi apply. */
public final class DimensionFreezeState implements DimensionFreeze {
    private final ThreadLocal<Integer> authorizationDepth = ThreadLocal.withInitial(() -> 0);
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

    public boolean isMutationAllowed() {
        return !isFrozen() || authorizationDepth.get() > 0;
    }

    public void runAuthorized(Runnable mutation) {
        Objects.requireNonNull(mutation, "mutation");
        int previous = authorizationDepth.get();
        authorizationDepth.set(previous + 1);
        try {
            mutation.run();
        } finally {
            if (previous == 0) {
                authorizationDepth.remove();
            } else {
                authorizationDepth.set(previous);
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
                released = true;
                frozen = false;
            }
        }
    }
}
