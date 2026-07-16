package io.github.lumi.telemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TelemetrySettingsRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void absentDefaultsEnabledButCorruptStateFailsClosed() throws Exception {
        Path file = tempDir.resolve("telemetry.properties");
        TelemetrySettingsRepository repository = new TelemetrySettingsRepository(file);
        assertTrue(repository.load().enabled());

        repository.save(new TelemetrySettings(false, true));
        assertFalse(repository.load().enabled());

        Files.writeString(file, "enabled=maybe");
        assertFalse(repository.load().enabled());
    }
}
