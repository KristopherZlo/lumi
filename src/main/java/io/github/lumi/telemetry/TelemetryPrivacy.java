package io.github.lumi.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** The only payload fields permitted to cross the telemetry boundary. */
final class TelemetryPrivacy {
    private static final int MAX_VALUE_LENGTH = 256;
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "operation", "stage", "failureClass", "failureFrame",
            "elapsedMicros", "budgetMicros");

    private TelemetryPrivacy() { }

    static Map<String, String> sanitize(Map<String, String> raw) {
        var safe = new LinkedHashMap<String, String>();
        if (raw == null) {
            return Map.of();
        }
        for (var entry : raw.entrySet()) {
            if (!ALLOWED_FIELDS.contains(entry.getKey())) {
                continue;
            }
            String value = entry.getValue() == null ? "" : entry.getValue()
                    .replace('\r', ' ').replace('\n', ' ').trim();
            safe.put(entry.getKey(), value.substring(0,
                    Math.min(value.length(), MAX_VALUE_LENGTH)));
        }
        return Map.copyOf(safe);
    }
}
