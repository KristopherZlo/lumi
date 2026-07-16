package io.github.lumi.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TelemetryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void failureUsesAllowlistedPayloadAndSuccessfulSendClearsQueue() {
        var sent = new ArrayList<TelemetryEvent>();
        TelemetryService service = service(events -> {
            sent.addAll(events);
            return true;
        });

        service.recordFailure("Restore", "VERIFY",
                new IllegalStateException("world=secret"));

        assertEquals(1, sent.size());
        assertEquals("Restore", sent.getFirst().payload().get("operation"));
        assertEquals(1, sent.getFirst().schemaVersion());
        assertEquals("1.21.11", sent.getFirst().environment().minecraftVersion());
        assertFalse(sent.getFirst().payload().containsKey("dimension"));
        assertEquals(0, service.pendingEventCount());
    }

    @Test
    void failedSendStaysBoundedAndDisablingDeletesTheQueue() {
        TelemetryService service = service(events -> false);
        service.recordFailure("Save", "WRITE", new RuntimeException("failed"));
        assertEquals(1, service.pendingEventCount());

        service.setEnabled(false);
        assertEquals(0, service.pendingEventCount());
        assertFalse(service.settings().enabled());
    }

    private TelemetryService service(
            java.util.function.Predicate<List<TelemetryEvent>> sender) {
        return new TelemetryService(
                new TelemetrySettingsRepository(tempDir.resolve("settings")),
                new TelemetrySpoolRepository(tempDir.resolve("spool"), 200),
                Runnable::run, sender, "0.2", "1.21.11");
    }
}
