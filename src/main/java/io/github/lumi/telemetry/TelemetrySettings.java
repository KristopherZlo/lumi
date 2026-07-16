package io.github.lumi.telemetry;

public record TelemetrySettings(boolean enabled, boolean noticeSeen) {
    public static TelemetrySettings defaults() {
        return new TelemetrySettings(true, false);
    }

    public TelemetrySettings withEnabled(boolean value) {
        return new TelemetrySettings(value, noticeSeen);
    }

    public TelemetrySettings withNoticeSeen() {
        return new TelemetrySettings(enabled, true);
    }
}
