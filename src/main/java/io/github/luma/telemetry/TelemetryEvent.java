package io.github.luma.telemetry;

import java.time.Instant;
import java.util.Map;

public record TelemetryEvent(
        String id,
        int schemaVersion,
        TelemetryEventType type,
        Instant occurredAt,
        String installationId,
        TelemetryEnvironment environment,
        String fingerprint,
        Map<String, String> payload
) {

    public static final int SCHEMA_VERSION = 1;

    public TelemetryEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
