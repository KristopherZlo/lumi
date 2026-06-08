package io.github.luma.telemetry;

public record TelemetryHttpResponse(
        int statusCode,
        String body
) {
}
