package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LumiBranchDropdownTest {
    @Test
    void boundsMenuHeightWithoutBoundingTheBranchList() {
        assertEquals(2, LumiBranchDropdown.visibleRows(36, 20));
        assertEquals(8, LumiBranchDropdown.visibleRows(400, 20));
        assertEquals("roof", LumiBranchDropdown.shortName("workspace/id/roof"));
    }

    @Test
    void animatesHoverInBothDirections() {
        assertEquals(0.5F,
                LumiBranchDropdown.hoverProgress(0F, true, 60));
        assertEquals(0.25F,
                LumiBranchDropdown.hoverProgress(0.75F, false, 60));
        assertTrue(LumiBranchDropdown.hoverProgress(0.9F, true, 60) <= 1F);
    }
}
