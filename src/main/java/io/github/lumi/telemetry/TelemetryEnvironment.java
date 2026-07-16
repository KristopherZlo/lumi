package io.github.lumi.telemetry;

/** Minimal backend-compatible environment; mod lists and machine identity are excluded. */
public record TelemetryEnvironment(String lumiVersion, String minecraftVersion) {
    public TelemetryEnvironment {
        lumiVersion = bounded(lumiVersion);
        minecraftVersion = bounded(minecraftVersion);
    }

    private static String bounded(String value) {
        String safe = value == null ? "unknown" : value.trim();
        return safe.substring(0, Math.min(64, safe.length()));
    }
}
