package io.github.lumi.minecraft.operation;

public enum RestoreStatus {
    APPLYING,
    VERIFYING,
    REPAIRING,
    RETURNING,
    COMPLETE,
    RETURNED,
    CANCELLED,
    DEGRADED
}
