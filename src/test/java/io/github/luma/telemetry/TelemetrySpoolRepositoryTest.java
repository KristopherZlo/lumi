package io.github.luma.telemetry;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetrySpoolRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsReloadsAndDropsOldestEventsWhenCapacityIsExceeded() {
        TelemetrySpoolRepository repository = new TelemetrySpoolRepository(this.tempDir.resolve("telemetry-spool.json"), 2);

        repository.save(List.of(
                event("oldest"),
                event("middle"),
                event("newest")
        ));

        List<TelemetryEvent> loaded = repository.load();

        assertEquals(2, loaded.size());
        assertEquals("middle", loaded.get(0).id());
        assertEquals("newest", loaded.get(1).id());
    }

    @Test
    void malformedFileLoadsAsEmptyQueue() throws Exception {
        Path file = this.tempDir.resolve("telemetry-spool.json");
        java.nio.file.Files.writeString(file, "{not-json");

        assertTrue(new TelemetrySpoolRepository(file, 5).load().isEmpty());
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
}
