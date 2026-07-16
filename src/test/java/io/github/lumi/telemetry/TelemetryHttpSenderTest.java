package io.github.lumi.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelemetryHttpSenderTest {
    @Test
    void writesTheDeployedBackendSchema() {
        TelemetryEvent event = new TelemetryEvent(
                "event-a", 1, TelemetryEventType.OPERATION_FAILED,
                "2026-01-01T00:00:00Z", new TelemetryEnvironment("2", "1.21.11"),
                Map.of("operation", "Restore"));

        var body = JsonParser.parseString(
                TelemetryHttpSender.body(List.of(event))).getAsJsonObject();

        assertEquals(1, body.get("schemaVersion").getAsInt());
        assertEquals("1.21.11", body.getAsJsonArray("events").get(0).getAsJsonObject()
                .getAsJsonObject("environment").get("minecraftVersion").getAsString());
    }
}
