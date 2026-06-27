package io.github.luma.client.telemetry;

import io.github.luma.telemetry.TelemetrySettings;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryNoticeControllerTest {

    @Test
    void hiddenWhenNoticeHasAlreadyBeenSeen() {
        TelemetryNoticeController controller = new TelemetryNoticeController(
                () -> new TelemetrySettings(1, true, 1, "https://telemetry.example.test/v1/events/batch", "install-a", Instant.now(), false),
                () -> {
                }
        );

        assertFalse(controller.shouldShowNotice());
    }

    @Test
    void acknowledgeMarksNoticeAsSeen() {
        AtomicBoolean acknowledged = new AtomicBoolean(false);
        TelemetryNoticeController controller = new TelemetryNoticeController(
                () -> new TelemetrySettings(1, true, 0, "https://telemetry.example.test/v1/events/batch", "install-a", Instant.now(), false),
                () -> acknowledged.set(true)
        );

        assertTrue(controller.shouldShowNotice());
        controller.acknowledgeNotice();
        assertTrue(acknowledged.get());
    }

    @Test
    void disabledTelemetryDoesNotShowNotice() {
        TelemetryNoticeController controller = new TelemetryNoticeController(
                () -> new TelemetrySettings(1, false, 0, "https://telemetry.example.test/v1/events/batch", "install-a", Instant.now(), false),
                () -> {
                }
        );

        assertFalse(controller.shouldShowNotice());
    }
}
