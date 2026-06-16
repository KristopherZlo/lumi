package io.github.luma.minecraft.world;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

/**
 * Tracks repeated apply failures by phase and block position so replay paths can
 * stop retrying bad payloads without turning one corrupt target into a tick
 * loop.
 */
final class WorldApplyExceptionTracker {

    static final int DEFAULT_MAX_FAILURES_PER_TARGET = 3;
    static final int DEFAULT_MAX_DETAIL_EVENTS = 16;

    private final int maxFailuresPerTarget;
    private final int maxDetailEvents;
    private final Map<String, FailureState> failures = new LinkedHashMap<>();
    private int totalFailures;
    private int quarantinedTargets;
    private int detailEvents;

    WorldApplyExceptionTracker() {
        this(DEFAULT_MAX_FAILURES_PER_TARGET, DEFAULT_MAX_DETAIL_EVENTS);
    }

    WorldApplyExceptionTracker(int maxFailuresPerTarget, int maxDetailEvents) {
        this.maxFailuresPerTarget = Math.max(1, maxFailuresPerTarget);
        this.maxDetailEvents = Math.max(0, maxDetailEvents);
    }

    synchronized boolean isQuarantined(String phase, BlockPos pos) {
        FailureState state = this.failures.get(key(phase, pos));
        return state != null && state.quarantined;
    }

    synchronized FailureDecision recordFailure(String phase, BlockPos pos, Exception exception) {
        String key = key(phase, pos);
        FailureState state = this.failures.computeIfAbsent(key, ignored -> new FailureState());
        this.totalFailures += 1;
        state.count += 1;
        boolean newlyQuarantined = false;
        if (!state.quarantined && state.count >= this.maxFailuresPerTarget) {
            state.quarantined = true;
            newlyQuarantined = true;
            this.quarantinedTargets += 1;
        }
        boolean detailAllowed = this.detailEvents < this.maxDetailEvents
                && (state.count == 1 || newlyQuarantined);
        if (detailAllowed) {
            this.detailEvents += 1;
        }
        return new FailureDecision(
                normalize(phase),
                pos == null ? "" : pos.getX() + ":" + pos.getY() + ":" + pos.getZ(),
                exception == null ? "Exception" : exception.getClass().getSimpleName(),
                exception == null || exception.getMessage() == null ? "" : exception.getMessage(),
                state.count,
                this.totalFailures,
                state.quarantined,
                detailAllowed,
                this.detailEvents >= this.maxDetailEvents
        );
    }

    synchronized void clear(String phase, BlockPos pos) {
        FailureState state = this.failures.get(key(phase, pos));
        if (state == null || state.quarantined) {
            return;
        }
        this.failures.remove(key(phase, pos));
    }

    synchronized int totalFailures() {
        return this.totalFailures;
    }

    synchronized int quarantinedTargets() {
        return this.quarantinedTargets;
    }

    synchronized int activeFailureTargets() {
        int active = 0;
        for (FailureState state : this.failures.values()) {
            if (!state.quarantined) {
                active += 1;
            }
        }
        return active;
    }

    private static String key(String phase, BlockPos pos) {
        return normalize(phase) + "|"
                + (pos == null ? "unknown" : Long.toString(pos.asLong()));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private static final class FailureState {

        private int count;
        private boolean quarantined;
    }

    record FailureDecision(
            String phase,
            String position,
            String exceptionClass,
            String message,
            int failureCount,
            int totalFailures,
            boolean quarantined,
            boolean logDetail,
            boolean detailLimitReached
    ) {
    }
}
