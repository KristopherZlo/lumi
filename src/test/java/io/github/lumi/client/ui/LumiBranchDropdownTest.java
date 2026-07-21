package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LumiBranchDropdownTest {
    @Test
    void boundsMenuHeightWithoutBoundingTheBranchList() {
        assertEquals(2, LumiBranchDropdown.visibleRows(36, 20));
        assertEquals(8, LumiBranchDropdown.visibleRows(400, 20));
        assertEquals("roof", LumiBranchDropdown.shortName("workspace/id/roof"));
    }
}
