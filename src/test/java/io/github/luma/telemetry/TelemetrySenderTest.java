package io.github.luma.telemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetrySenderTest {

    @Test
    void sendsBatchAsJsonAndReportsAcceptedEvents() {
        CapturingTransport transport = new CapturingTransport(202, "{\"accepted\":1}");
        TelemetrySender sender = new TelemetrySender(transport, Duration.ofSeconds(1));

        TelemetrySendResult result = sender.send(
                "https://telemetry.example.test/v1/events/batch",
                List.of(event("event-a"))
        );

        assertTrue(result.success());
        assertEquals(1, result.acceptedEvents());
        assertTrue(transport.body.contains("\"schemaVersion\":1"));
        assertTrue(transport.body.contains("\"event-a\""));
    }

    @Test
    void keepsQueuedEventsWhenServerRejectsOrResponseIsMalformed() {
        TelemetrySender rejected = new TelemetrySender(new CapturingTransport(500, "{}"), Duration.ofSeconds(1));
        TelemetrySender malformed = new TelemetrySender(new CapturingTransport(202, "{not-json"), Duration.ofSeconds(1));

        assertFalse(rejected.send("https://telemetry.example.test/v1/events/batch", List.of(event("a"))).success());
        assertFalse(malformed.send("https://telemetry.example.test/v1/events/batch", List.of(event("b"))).success());
    }

    private static TelemetryEvent event(String id) {
        return new TelemetryEvent(
                id,
                1,
                TelemetryEventType.OPERATION_REJECTED,
                Instant.parse("2026-01-01T00:00:00Z"),
                "install-a",
                new TelemetryEnvironment("lumi", "minecraft", "fabric", "java", "os", "arch", List.of()),
                "fingerprint-" + id,
                Map.of("statusKey", "luma.status.operation_failed")
        );
    }

    private static final class CapturingTransport implements TelemetryHttpTransport {

        private final int statusCode;
        private final String responseBody;
        private String body = "";

        private CapturingTransport(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        @Override
        public TelemetryHttpResponse postJson(String endpointUrl, String body, Duration timeout) {
            this.body = body;
            return new TelemetryHttpResponse(this.statusCode, this.responseBody);
        }
    }
}
