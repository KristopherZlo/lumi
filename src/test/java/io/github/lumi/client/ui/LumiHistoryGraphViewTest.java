package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class LumiHistoryGraphViewTest {
    @Test
    void lanePaletteIsStableAndCyclesWithoutInvalidIndexes() {
        assertEquals(LumiHistoryGraphNodeButton.laneColor(0),
                LumiHistoryGraphNodeButton.laneColor(8));
        assertEquals(LumiHistoryGraphNodeButton.laneColor(7),
                LumiHistoryGraphNodeButton.laneColor(-1));
        assertNotEquals(LumiHistoryGraphNodeButton.laneColor(0),
                LumiHistoryGraphNodeButton.laneColor(1));
    }

    @Test
    void tooltipFollowsThePointerAndClampsToTheViewport() {
        assertEquals(new LumiHistoryGraphView.TooltipPosition(28, 28),
                LumiHistoryGraphView.tooltipPosition(
                        20, 20, 0, 0, 200, 100, 100, 56));
        assertEquals(new LumiHistoryGraphView.TooltipPosition(100, 44),
                LumiHistoryGraphView.tooltipPosition(
                        190, 90, 0, 0, 200, 100, 100, 56));
    }

    @Test
    void graphNodesDoNotInstallTheObstructiveOpenTooltip() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiHistoryGraphNodeButton.java"));

        assertFalse(source.contains("setTooltip("));
        org.junit.jupiter.api.Assertions.assertTrue(source.contains(
                "nodeX - 5, nodeY - 5, 11, 11"));
    }
}
