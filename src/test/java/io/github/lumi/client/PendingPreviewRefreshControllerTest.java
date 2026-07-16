package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        controller.tick(false, true);
        controller.tick(true, true);

        assertEquals(2, refreshes.get());
    }
}
