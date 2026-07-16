package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LumiOperationHudTest {
    @Test
    void stacksOperationBelowCollapsedOrExpandedWorkspacePanel() {
        assertEquals(38, LumiOperationHud.nextPanelY(10, 22));
        assertEquals(84, LumiOperationHud.nextPanelY(10, 68));
    }
}
