package io.github.luma.integration.common;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Guards stack-trace based external-tool fallback detection behind an explicit
 * runtime opt-in so normal Minecraft mutation paths do not allocate stack
 * traces on every block change.
 */
final class ExternalToolStackTraceDetectionGate {

    static final String ENABLED_FLAG = "lumi.externalStackDetection";

    private final BooleanSupplier externalToolAvailable;
    private final BooleanSupplier stackDetectionEnabled;
    private volatile Boolean available;

    ExternalToolStackTraceDetectionGate(BooleanSupplier externalToolAvailable) {
        this(externalToolAvailable, () -> Boolean.getBoolean(ENABLED_FLAG));
    }

    ExternalToolStackTraceDetectionGate(
            BooleanSupplier externalToolAvailable,
            BooleanSupplier stackDetectionEnabled
    ) {
        this.externalToolAvailable = Objects.requireNonNull(externalToolAvailable, "externalToolAvailable");
        this.stackDetectionEnabled = Objects.requireNonNull(stackDetectionEnabled, "stackDetectionEnabled");
    }

    boolean available() {
        if (!this.stackDetectionEnabled.getAsBoolean()) {
            return false;
        }

        Boolean cached = this.available;
        if (cached != null) {
            return cached;
        }

        boolean detected = this.externalToolAvailable.getAsBoolean();
        this.available = detected;
        return detected;
    }
}
