package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockBox;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ZoneScreenControllerTest {
    @Test
    void requiresSelectionAndSendsOneTrimmedCreateIntent() {
        var requests = new ArrayList<String>();
        ZoneScreenController controller = new ZoneScreenController(
                (name, area) -> requests.add(name + ":" + area.minX()));
        BlockBox area = new BlockBox(4, 5, 6, 7, 8, 9);

        assertEquals("luma.status.zone_name_required",
                controller.create(" ", Optional.of(area)).error());
        assertEquals("luma.selection.no_selection",
                controller.create("Clock", Optional.empty()).error());
        ZoneScreenController.Submission accepted =
                controller.create("  Clock  ", Optional.of(area));

        assertTrue(accepted.accepted());
        assertEquals(java.util.List.of("Clock:4"), requests);
    }

    @Test
    void reportsRejectedCreateIntent() {
        ZoneScreenController controller = new ZoneScreenController((name, area) -> {
            throw new IllegalStateException("Zone is outside the workspace");
        });

        ZoneScreenController.Submission result = controller.create(
                "Clock", Optional.of(new BlockBox(0, 0, 0, 0, 0, 0)));

        assertEquals(false, result.accepted());
        assertEquals("Zone is outside the workspace", result.error());
    }
}
