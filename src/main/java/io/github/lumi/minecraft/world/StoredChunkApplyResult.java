package io.github.lumi.minecraft.world;

/** Result and timings of one gated vanilla-storage attempt. */
public record StoredChunkApplyResult(
        boolean applied,
        long readNanos,
        long writeNanos,
        long syncNanos,
        long verifyNanos) {
    public static final StoredChunkApplyResult APPLIED = applied(0, 0, 0, 0);
    public static final StoredChunkApplyResult FALLBACK = new StoredChunkApplyResult(
            false, 0, 0, 0, 0);

    public static StoredChunkApplyResult applied(
            long readNanos, long writeNanos, long syncNanos, long verifyNanos) {
        return new StoredChunkApplyResult(
                true, readNanos, writeNanos, syncNanos, verifyNanos);
    }
}
