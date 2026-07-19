package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiZonesScreenTest {
    @Test
    void compactLayoutKeepsZoneRowsReachable() {
        int[][] viewports = {{320, 180}, {427, 240}, {640, 360}};
        int[] expectedRows = {1, 2, 6};
        for (int index = 0; index < viewports.length; index++) {
            LegacyWorkspaceLayout layout = LegacyWorkspaceLayout.fit(
                    viewports[index][0], viewports[index][1]);
            boolean compact = layout.contentWidth() < 300;
            int rows = LumiZonesScreen.visibleRows(layout.windowHeight(), compact);

            assertEquals(expectedRows[index], rows);
            int offset = compact ? 118 : 126;
            int height = compact ? 42 : 28;
            int stride = compact ? 46 : 32;
            assertTrue(offset + (rows - 1) * stride + height
                    <= layout.windowHeight());
        }
    }

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
        assertTrue(source.contains("\"luma.zones.cells\", zone.cells()"));
        assertTrue(source.contains("public boolean mouseScrolled("));
        assertTrue(source.contains("compact = panelWidth < 300"));
        assertTrue(source.contains("rowHeight = compact ? 42 : 28"));
        assertTrue(source.contains("name.getWidth() + 4"));
    }
}
