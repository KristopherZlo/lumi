package io.github.luma.telemetry;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TelemetrySettingsRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void testEnvironmentUsesDisabledTransientDefaultsAndDoesNotPersist() throws Exception {
        Path file = this.tempDir.resolve("lumi-telemetry.json");
        TelemetrySettingsRepository repository = new TelemetrySettingsRepository(
                file,
                "https://telemetry.example.test/v1/events/batch",
                () -> "install-a",
                true
        );

        TelemetrySettings settings = repository.load();
        repository.save(settings.withEnabled(true));

        assertFalse(settings.enabled());
        assertEquals("", settings.endpointUrl());
        assertFalse(Files.exists(file));
    }
}
