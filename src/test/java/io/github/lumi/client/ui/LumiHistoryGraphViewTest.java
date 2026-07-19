package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
}
