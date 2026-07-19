package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class PackageScreenControllerTest {
    @Test
    void validatesAndSendsOneLogicalPackageName() {
        var exports = new ArrayList<String>();
        var inspections = new ArrayList<String>();
        PackageScreenController controller =
                new PackageScreenController(
                        (name, includePreview) -> exports.add(
                                name + ":" + includePreview),
                        inspections::add);

        assertEquals(false, controller.submit(
                "../outside", PackageScreenController.Action.EXPORT).accepted());
        var exported = controller.submit(
                " clock-v2 ", PackageScreenController.Action.EXPORT);
        var inspected = controller.submit(
                "clock-v2", PackageScreenController.Action.INSPECT);

        assertTrue(exported.accepted());
        assertTrue(inspected.accepted());
        assertEquals(java.util.List.of("clock-v2:false"), exports);
        assertEquals(java.util.List.of("clock-v2"), inspections);
    }
}
