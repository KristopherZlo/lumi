package io.github.luma.telemetry;

import java.util.List;

@FunctionalInterface
public interface TelemetryBatchSender {

    TelemetrySendResult send(String endpointUrl, List<TelemetryEvent> events);
}
