package io.github.luma.domain.model;

import java.time.Instant;

/** Current history reliability state and, when degraded, its durable reason. */
public record HistoryProtectionStatus(
        HistoryProtectionState state,
        String detail,
        Instant updatedAt
) {

    public HistoryProtectionStatus {
        state = state == null ? HistoryProtectionState.PROTECTED : state;
        detail = detail == null ? "" : detail;
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }

    public static HistoryProtectionStatus protectedStatus() {
        return new HistoryProtectionStatus(HistoryProtectionState.PROTECTED, "", Instant.EPOCH);
    }

    public static HistoryProtectionStatus active(HistoryProtectionState state, String detail, Instant updatedAt) {
        if (state != HistoryProtectionState.SAVING && state != HistoryProtectionState.RESTORING) {
            throw new IllegalArgumentException("Active history state must be saving or restoring");
        }
        return new HistoryProtectionStatus(state, detail, updatedAt);
    }

    public static HistoryProtectionStatus degraded(String detail, Instant updatedAt) {
        String reason = detail == null || detail.isBlank() ? "History reliability check failed" : detail;
        return new HistoryProtectionStatus(HistoryProtectionState.DEGRADED, reason, updatedAt);
    }
}
