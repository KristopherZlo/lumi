package io.github.lumi.telemetry;

import java.util.Map;
import java.util.Objects;

public record TelemetryEvent(
        String id,
        TelemetryEventType type,
        long occurredAtEpochMillis,
        String lumiVersion,
        String minecraftVersion,
        Map<String, String> payload) {

    public TelemetryEvent {
        if (id == null || id.isBlank() || id.length() > 64) {
            throw new IllegalArgumentException("Invalid telemetry event ID");
        }
        Objects.requireNonNull(type, "type");
        lumiVersion = bounded(lumiVersion);
        minecraftVersion = bounded(minecraftVersion);
        payload = TelemetryPrivacy.sanitize(payload);
    }

    private static String bounded(String value) {
        String safe = value == null ? "unknown" : value.trim();
        return safe.substring(0, Math.min(64, safe.length()));
    }
}
