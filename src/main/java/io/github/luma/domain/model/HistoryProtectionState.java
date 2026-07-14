package io.github.luma.domain.model;

/** User-facing reliability state for one project's history. */
public enum HistoryProtectionState {
    PROTECTED,
    SAVING,
    RESTORING,
    DEGRADED
}
