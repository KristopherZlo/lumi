package io.github.luma.minecraft.capture;

public final class WorldMutationCaptureGuard {

    private static final ThreadLocal<Integer> LEVEL_SET_BLOCK_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> CHUNK_SET_BLOCK_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> DIRECT_SECTION_SUPPRESSION_DEPTH = ThreadLocal.withInitial(() -> 0);

    private WorldMutationCaptureGuard() {
    }

    public static CaptureBoundary pushLevelSetBlockBoundary() {
        LEVEL_SET_BLOCK_DEPTH.set(LEVEL_SET_BLOCK_DEPTH.get() + 1);
        return new CaptureBoundary(LEVEL_SET_BLOCK_DEPTH);
    }

    public static void popLevelSetBlockBoundary() {
        pop(LEVEL_SET_BLOCK_DEPTH);
    }

    public static boolean isWithinLevelSetBlockBoundary() {
        return LEVEL_SET_BLOCK_DEPTH.get() > 0;
    }

    public static CaptureBoundary pushChunkSetBlockBoundary() {
        CHUNK_SET_BLOCK_DEPTH.set(CHUNK_SET_BLOCK_DEPTH.get() + 1);
        return new CaptureBoundary(CHUNK_SET_BLOCK_DEPTH);
    }

    public static void popChunkSetBlockBoundary() {
        pop(CHUNK_SET_BLOCK_DEPTH);
    }

    public static CaptureBoundary pushDirectSectionCaptureSuppression() {
        DIRECT_SECTION_SUPPRESSION_DEPTH.set(DIRECT_SECTION_SUPPRESSION_DEPTH.get() + 1);
        return new CaptureBoundary(DIRECT_SECTION_SUPPRESSION_DEPTH);
    }

    public static void popDirectSectionCaptureSuppression() {
        pop(DIRECT_SECTION_SUPPRESSION_DEPTH);
    }

    public static boolean suppressesDirectSectionCapture() {
        return LEVEL_SET_BLOCK_DEPTH.get() > 0
                || CHUNK_SET_BLOCK_DEPTH.get() > 0
                || DIRECT_SECTION_SUPPRESSION_DEPTH.get() > 0;
    }

    private static void pop(ThreadLocal<Integer> depth) {
        int currentDepth = depth.get();
        if (currentDepth <= 1) {
            depth.remove();
            return;
        }
        depth.set(currentDepth - 1);
    }

    public static final class CaptureBoundary implements AutoCloseable {

        private final ThreadLocal<Integer> depth;
        private boolean closed;

        private CaptureBoundary(ThreadLocal<Integer> depth) {
            this.depth = depth;
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            pop(this.depth);
        }
    }
}
