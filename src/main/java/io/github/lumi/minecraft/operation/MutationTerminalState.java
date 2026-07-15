package io.github.lumi.minecraft.operation;

/** Stable terminal result used by logs, packets and operation history. */
public enum MutationTerminalState {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    RETURNED,
    DEGRADED
}
