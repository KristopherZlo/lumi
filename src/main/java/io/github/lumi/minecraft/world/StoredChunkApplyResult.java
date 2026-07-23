package io.github.lumi.minecraft.world;

import java.util.Objects;

/** Result and timings of one gated vanilla-storage attempt. */
public record StoredChunkApplyResult(
        Outcome outcome,
        long readNanos,
        long writeNanos,
        long syncNanos,
        long verifyNanos) {
    public StoredChunkApplyResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    public static final StoredChunkApplyResult APPLIED = applied(0, 0, 0, 0);
    public static final StoredChunkApplyResult FALLBACK = fallback(Outcome.UNAVAILABLE);

    public boolean applied() {
        return outcome == Outcome.APPLIED;
    }

    public static StoredChunkApplyResult applied(
            long readNanos, long writeNanos, long syncNanos, long verifyNanos) {
        return new StoredChunkApplyResult(
                Outcome.APPLIED, readNanos, writeNanos, syncNanos, verifyNanos);
    }

    public static StoredChunkApplyResult fallback(Outcome outcome) {
        if (outcome == Outcome.APPLIED) {
            throw new IllegalArgumentException("Applied is not a fallback outcome");
        }
        return new StoredChunkApplyResult(outcome, 0, 0, 0, 0);
    }

    public enum Outcome {
        APPLIED,
        UNAVAILABLE,
        ENTITY_CHANGES,
        UNSUPPORTED_DELTA,
        RESIDENT,
        MISSING,
        UNSUPPORTED_STORAGE
    }
}
