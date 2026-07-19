package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ZoneScreenControllerTest {
    @Test
    void createsAnEmptyZoneWithOneTrimmedIntent() {
        var requests = new ArrayList<String>();
        ZoneScreenController controller = new ZoneScreenController(
                requests::add);

        assertEquals("luma.status.zone_name_required",
                controller.create(" ").error());
        ZoneScreenController.Submission accepted =
                controller.create("  Clock  ");

        assertTrue(accepted.accepted());
        assertEquals(java.util.List.of("Clock"), requests);
    }

    @Test
    void reportsRejectedCreateIntent() {
        ZoneScreenController controller = new ZoneScreenController(name -> {
            throw new IllegalStateException("Zone could not be created");
        });

        ZoneScreenController.Submission result = controller.create("Clock");

        assertEquals(false, result.accepted());
        assertEquals("Zone could not be created", result.error());
    }
}
