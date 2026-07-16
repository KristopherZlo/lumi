package io.github.lumi.telemetry;

import java.util.Map;
import java.util.Objects;

public record TelemetryEvent(
        String id,
        int schemaVersion,
        TelemetryEventType type,
        String occurredAt,
        TelemetryEnvironment environment,
        Map<String, String> payload) {

    public TelemetryEvent {
        if (id == null || id.isBlank() || id.length() > 64) {
            throw new IllegalArgumentException("Invalid telemetry event ID");
        }
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported telemetry schema");
        }
        Objects.requireNonNull(type, "type");
        if (occurredAt == null || occurredAt.isBlank() || occurredAt.length() > 64) {
            throw new IllegalArgumentException("Invalid telemetry timestamp");
        }
        Objects.requireNonNull(environment, "environment");
        payload = TelemetryPrivacy.sanitize(payload);
    }
}
