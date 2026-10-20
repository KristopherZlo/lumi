package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PendingPreviewRefreshControllerTest {
    @Test
    void refreshesOnceForEachAltPress() {
        AtomicInteger refreshes = new AtomicInteger();
        PendingPreviewRefreshController controller =
                new PendingPreviewRefreshController(refreshes::incrementAndGet);

        controller.tick(false, true);
        controller.tick(true, true);
        controller.tick(true, true);
        controller.tick(false, true);
        controller.tick(true, false);
        controller.tick(true, true);
        controller.tick(true, true);
        controller.tick(false, true);
        controller.tick(true, true);

        assertEquals(3, refreshes.get());
    }

    @Test
    void pinsTheGuiPreviewUntilHiddenAndRefreshesOnlyWhenShownAgain() {
        AtomicInteger refreshes = new AtomicInteger();
        var controller = new PendingPreviewRefreshController(refreshes::incrementAndGet);
        assertTrue(controller.toggleVisibility().isEmpty());
        controller.show();
        controller.tick(false, true);
        assertTrue(controller.visible());
        assertEquals(0, refreshes.get());
        assertEquals(false, controller.toggleVisibility().orElseThrow());
        assertFalse(controller.visible());
        assertEquals(0, refreshes.get());
        assertEquals(true, controller.toggleVisibility().orElseThrow());
        assertEquals(1, refreshes.get());
        controller.tick(false, true);
        assertEquals(1, refreshes.get());
        controller.clear();
        assertFalse(controller.visible());
        assertTrue(controller.toggleVisibility().isEmpty());
    }
}
