package io.github.luma.telemetry;

import java.time.Duration;

public interface TelemetryHttpTransport {

    TelemetryHttpResponse postJson(String endpointUrl, String body, Duration timeout) throws Exception;
}
