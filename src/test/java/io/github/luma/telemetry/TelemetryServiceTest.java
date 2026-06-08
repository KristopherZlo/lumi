package io.github.luma.telemetry;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void disabledTelemetryDropsEventsAndClearsSpool() {
        TelemetrySpoolRepository spool = new TelemetrySpoolRepository(this.tempDir.resolve("telemetry-spool.json"), 10);
        spool.save(List.of(event("queued")));
        TelemetryService service = TelemetryService.testing(
                new TelemetrySettings(1, false, 0, "https://telemetry.example.test/v1/events/batch", "install-a", Instant.parse("2026-01-01T00:00:00Z")),
                spool,
                new TelemetryEnvironmentProvider.Static(new TelemetryEnvironment("lumi", "mc", "loader", "java", "os", "arch", List.of())),
                Runnable::run,
                (endpoint, events) -> TelemetrySendResult.success(events.size())
        );

        service.recordOperationRejected("save", "luma.status.operation_failed", new IllegalStateException("C:\\Users\\Alex\\world"));
        service.flushNow();

        assertTrue(spool.load().isEmpty());
    }

    @Test
    void enabledTelemetryQueuesSanitizedRejectedAction() {
        TelemetrySpoolRepository spool = new TelemetrySpoolRepository(this.tempDir.resolve("telemetry-spool.json"), 10);
        TelemetryService service = TelemetryService.testing(
                TelemetrySettings.defaults("https://telemetry.example.test/v1/events/batch", () -> "install-a"),
                spool,
                new TelemetryEnvironmentProvider.Static(new TelemetryEnvironment("lumi", "mc", "loader", "java", "os", "arch", List.of())),
                Runnable::run,
                (endpoint, events) -> TelemetrySendResult.failure("offline")
        );

        service.recordOperationRejected("save", "luma.status.operation_failed", new IllegalStateException("C:\\Users\\Alex\\world"));
        service.flushNow();

        List<TelemetryEvent> events = spool.load();
        assertEquals(1, events.size());
        assertEquals(TelemetryEventType.OPERATION_REJECTED, events.getFirst().type());
        assertEquals("save", events.getFirst().payload().get("action"));
        assertEquals("<path>", events.getFirst().payload().get("failure"));
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
                java.util.Map.of("statusKey", "luma.status.operation_failed")
        );
    }
}
