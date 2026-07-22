package io.github.lumi.minecraft.operation;

public enum RestoreStatus {
    APPLYING,
    VERIFYING,
    REPAIRING,
    PERSISTING,
    PUBLISHING,
    RETURNING,
    COMPLETE,
    RETURNED,
    CANCELLED,
    DEGRADED
}
