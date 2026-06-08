package io.github.luma.client.telemetry;

import io.github.luma.telemetry.TelemetrySettings;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetrySettingsPanelControllerTest {

    @Test
    void exposesCurrentStateAndActions() {
        AtomicBoolean enabled = new AtomicBoolean(true);
        AtomicInteger pending = new AtomicInteger(3);
        AtomicBoolean cleared = new AtomicBoolean(false);
        AtomicBoolean toggled = new AtomicBoolean(false);
        TelemetrySettingsPanelController controller = new TelemetrySettingsPanelController(
                () -> new TelemetrySettings(1, enabled.get(), 1, "https://telemetry.example.test/v1/events/batch", "install-a", Instant.now()),
                value -> {
                    enabled.set(value);
                    toggled.set(true);
                },
                pending::get,
                () -> "accepted-1",
                () -> cleared.set(true)
        );

        assertTrue(controller.enabled());
        assertEquals("https://telemetry.example.test/v1/events/batch", controller.endpointUrl());
        assertEquals(3, controller.pendingEventCount());
        assertEquals("accepted-1", controller.lastSendSummary());

        controller.setEnabled(false);
        controller.clearLocalQueue();

        assertFalse(controller.enabled());
        assertTrue(toggled.get());
        assertTrue(cleared.get());
    }
}
