package io.github.lumi.minecraft.world;

import java.util.Objects;

/** Result and timings of one gated vanilla-storage attempt. */
public record StoredChunkApplyResult(
        Outcome outcome,
        long sectionSwaps,
        long changedBlocks,
        long lightSections,
        long readNanos,
        long writeNanos,
        long syncNanos,
        long verifyNanos) {
    public StoredChunkApplyResult {
        Objects.requireNonNull(outcome, "outcome");
        for (long value : new long[] {
                sectionSwaps, changedBlocks, lightSections,
                readNanos, writeNanos, syncNanos, verifyNanos}) {
            if (value < 0) {
                throw new IllegalArgumentException(
                        "Stored chunk statistics cannot be negative");
            }
        }
    }

    public static final StoredChunkApplyResult APPLIED = applied(0, 0, 0, 0);
    public static final StoredChunkApplyResult FALLBACK = fallback(Outcome.UNAVAILABLE);

    public boolean applied() {
        return outcome == Outcome.APPLIED;
    }

    public boolean stagedResident() {
        return outcome == Outcome.STAGED_RESIDENT;
    }

    public boolean stored() {
        return applied() || stagedResident();
    }

    public static StoredChunkApplyResult applied(
            long readNanos, long writeNanos, long syncNanos, long verifyNanos) {
        return applied(readNanos, writeNanos, syncNanos, verifyNanos, 0, 0, 0);
    }

    public static StoredChunkApplyResult applied(
            long readNanos, long writeNanos, long syncNanos, long verifyNanos,
            long sectionSwaps, long changedBlocks, long lightSections) {
        return new StoredChunkApplyResult(
                Outcome.APPLIED, sectionSwaps, changedBlocks, lightSections,
                readNanos, writeNanos, syncNanos, verifyNanos);
    }

    public static StoredChunkApplyResult fallback(Outcome outcome) {
        if (outcome == Outcome.APPLIED || outcome == Outcome.STAGED_RESIDENT) {
            throw new IllegalArgumentException("Stored outcome is not a fallback");
        }
        return new StoredChunkApplyResult(outcome, 0, 0, 0, 0, 0, 0, 0);
    }

    public static StoredChunkApplyResult stagedResident(
            long readNanos, long writeNanos) {
        return new StoredChunkApplyResult(
                Outcome.STAGED_RESIDENT, 0, 0, 0,
                readNanos, writeNanos, 0, 0);
    }

    public enum Outcome {
        APPLIED,
        STAGED_RESIDENT,
        UNAVAILABLE,
        ENTITY_CHANGES,
        UNSUPPORTED_DELTA,
        RESIDENT,
        MISSING,
        UNSUPPORTED_STORAGE
    }
}
