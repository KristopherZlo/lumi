package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;

public final class WorldMutationContext {

    private static final ThreadLocal<WorldMutationSource> CURRENT_SOURCE = ThreadLocal.withInitial(() -> WorldMutationSource.PLAYER);

    private WorldMutationContext() {
    }

    public static WorldMutationSource currentSource() {
        return CURRENT_SOURCE.get();
    }

    public static void runWithSource(WorldMutationSource source, Runnable runnable) {
        WorldMutationSource previous = CURRENT_SOURCE.get();
        CURRENT_SOURCE.set(source);
        try {
            runnable.run();
        } finally {
            CURRENT_SOURCE.set(previous);
        }
    }
}
