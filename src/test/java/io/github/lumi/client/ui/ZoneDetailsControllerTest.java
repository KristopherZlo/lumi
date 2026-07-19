package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ZoneDetailsControllerTest {
    @Test
    void requiresActiveZoneAndSendsTrimmedSaveMessage() {
        UUID zone = new UUID(0, 7);
        var requests = new ArrayList<String>();
        ZoneDetailsController controller = new ZoneDetailsController(
                (id, message, tags) -> requests.add(id + ":" + message),
                (id, message, tags) -> requests.add("amend:" + id + ":" + message));

        assertEquals("luma.zones.save_enter_first",
                controller.save(zone, "Clock", false).error());
        assertEquals("luma.status.save_name_required",
                controller.save(zone, " ", true).error());
        ZoneDetailsController.Submission accepted =
                controller.save(zone, "  Clock works  ", true);

        assertTrue(accepted.accepted());
        assertEquals(java.util.List.of(zone + ":Clock works"), requests);
    }

    @Test
    void reportsRejectedSaveIntent() {
        ZoneDetailsController controller = new ZoneDetailsController(
                (zone, message, tags) -> {
                    throw new IllegalStateException("History changed");
                }, (zone, message, tags) -> { });

        ZoneDetailsController.Submission result =
                controller.save(new UUID(0, 7), "Clock", true);

        assertEquals(false, result.accepted());
        assertEquals("History changed", result.error());
    }

    @Test
    void sendsZoneAmendWithCanonicalTags() {
        UUID zone = new UUID(0, 9);
        var requests = new ArrayList<String>();
        ZoneDetailsController controller = new ZoneDetailsController(
                (id, message, tags) -> { },
                (id, message, tags) -> requests.add(
                        id + ":" + message + ":" + tags.serialize()));

        ZoneDetailsController.Submission result = controller.amend(
                zone, "  Clock works  ", " #Copper, redstone ", true);

        assertTrue(result.accepted());
        assertEquals(java.util.List.of(zone + ":Clock works:copper, redstone"), requests);
    }
}
