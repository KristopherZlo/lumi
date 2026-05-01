package io.github.luma.minecraft.world;

final class WorldApplyChunkLoadContext {

    private static final ThreadLocal<Integer> SYNCHRONOUS_LOAD_DEPTH = ThreadLocal.withInitial(() -> 0);

    private WorldApplyChunkLoadContext() {
    }

    static void pushAllowSynchronousLoad() {
        SYNCHRONOUS_LOAD_DEPTH.set(SYNCHRONOUS_LOAD_DEPTH.get() + 1);
    }

    static void pop() {
        int depth = SYNCHRONOUS_LOAD_DEPTH.get();
        if (depth <= 1) {
            SYNCHRONOUS_LOAD_DEPTH.remove();
            return;
        }
        SYNCHRONOUS_LOAD_DEPTH.set(depth - 1);
    }

    static boolean allowsSynchronousLoad() {
        return SYNCHRONOUS_LOAD_DEPTH.get() > 0;
    }
}
