package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiZonesScreenTest {
    @Test
    void exposesLegacyOverlayCycleAndZoneActions() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiZonesScreen.java"));

        assertTrue(source.contains("overlayLabel.get()"));
        assertTrue(source.contains("cycleOverlay.run()"));
        assertTrue(source.contains("luma.zones.delete"));
        assertTrue(source.contains("luma.action.open_details"));
        assertTrue(source.contains("zone.active() ? \"leave\" : \"join\""));
        assertTrue(source.contains("\"folder\""));
        assertTrue(source.contains("\"trash\""));
        assertTrue(source.contains("zone.color()"));
        assertTrue(source.contains("zone.cells() + \" cells\""));
        assertTrue(source.contains("public boolean mouseScrolled("));
    }
}
