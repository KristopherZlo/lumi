package io.github.luma.telemetry;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetrySettingsTest {

    @Test
    void defaultsEnableDiagnosticTelemetryWithoutMarkingNoticeSeen() {
        TelemetrySettings settings = TelemetrySettings.defaults("https://telemetry.example.test/v1/events/batch", () -> "install-a");

        assertTrue(settings.enabled());
        assertEquals("https://telemetry.example.test/v1/events/batch", settings.endpointUrl());
        assertEquals("install-a", settings.installationId());
        assertEquals(0, settings.noticeSeenVersion());
    }

    @Test
    void rotatesInstallationIdAfterThirtyDays() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        TelemetrySettings settings = new TelemetrySettings(
                1,
                true,
                1,
                "https://telemetry.example.test/v1/events/batch",
                "install-a",
                created,
                false
        );

        TelemetrySettings stable = settings.rotateIfExpired(created.plusSeconds(29L * 24L * 60L * 60L), () -> "install-b");
        TelemetrySettings rotated = settings.rotateIfExpired(created.plusSeconds(31L * 24L * 60L * 60L), () -> "install-c");

        assertEquals("install-a", stable.installationId());
        assertEquals("install-c", rotated.installationId());
        assertNotEquals(settings.rotatedAt(), rotated.rotatedAt());
    }
}
