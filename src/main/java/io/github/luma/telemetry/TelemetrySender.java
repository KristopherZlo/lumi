package io.github.luma.telemetry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.luma.storage.GsonProvider;
import java.time.Duration;
import java.util.List;

public final class TelemetrySender implements TelemetryBatchSender {

    private final TelemetryHttpTransport transport;
    private final Duration timeout;

    public TelemetrySender(TelemetryHttpTransport transport, Duration timeout) {
        this.transport = transport;
        this.timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
    }

    @Override
    public TelemetrySendResult send(String endpointUrl, List<TelemetryEvent> events) {
        if (events == null || events.isEmpty()) {
            return TelemetrySendResult.success(0);
        }
        try {
            String body = GsonProvider.compactGson().toJson(new Batch(TelemetryEvent.SCHEMA_VERSION, events));
            TelemetryHttpResponse response = this.transport.postJson(endpointUrl, body, this.timeout);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return TelemetrySendResult.failure("http-" + response.statusCode());
            }
            JsonObject json = JsonParser.parseString(response.body() == null ? "" : response.body()).getAsJsonObject();
            if (!json.has("accepted")) {
                return TelemetrySendResult.failure("missing-accepted");
            }
            return TelemetrySendResult.success(json.get("accepted").getAsInt());
        } catch (Exception exception) {
            return TelemetrySendResult.failure(exception.getClass().getSimpleName());
        }
    }

    private record Batch(
            int schemaVersion,
            List<TelemetryEvent> events
    ) {
    }
}
