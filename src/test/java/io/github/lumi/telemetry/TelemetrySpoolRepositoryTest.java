package io.github.lumi.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TelemetrySpoolRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void roundTripsOnlyTheNewestBoundedEvents() {
        TelemetrySpoolRepository repository =
                new TelemetrySpoolRepository(tempDir.resolve("spool.json"), 2);
        repository.save(List.of(event("a"), event("b"), event("c")));

        assertEquals(List.of("b", "c"),
                repository.load().stream().map(TelemetryEvent::id).toList());
    }

    private static TelemetryEvent event(String id) {
        return new TelemetryEvent(id, 1, TelemetryEventType.OPERATION_FAILED,
                "2026-01-01T00:00:00Z", new TelemetryEnvironment("0.2", "1.21.11"),
                Map.of("operation", "Restore"));
    }
}
