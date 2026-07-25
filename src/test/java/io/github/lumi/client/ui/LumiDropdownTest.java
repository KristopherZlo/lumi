package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class LumiDropdownTest {
    @Test
    void boundsMenuHeightWithoutBoundingTheBranchList() {
        assertEquals(2, LumiDropdown.visibleRows(36, 20));
        assertEquals(8, LumiDropdown.visibleRows(400, 20));
        assertEquals("roof", LumiDropdown.shortName("workspace/id/roof"));
        assertTrue(LumiDropdown.opensAbove(80, 18, 3));
        assertFalse(LumiDropdown.opensAbove(18, 80, 3));
    }

    @Test
    void animatesHoverInBothDirections() {
        assertEquals(0.5F,
                LumiDropdown.hoverProgress(0F, true, 60));
        assertEquals(0.25F,
                LumiDropdown.hoverProgress(0.75F, false, 60));
        assertTrue(LumiDropdown.hoverProgress(0.9F, true, 60) <= 1F);
    }

    @Test
    void alignsTheClosedSelectionWithRowsAndReservesTheChevron() {
        LumiDropdown<String> dropdown = new LumiDropdown<>(
                0, 0, 80, 0, 40,
                List.of("GUI"), "GUI", Component::literal, ignored -> { });

        assertEquals(36, dropdown.labelAvailable(50));
        assertTrue(dropdown.leftAlignedLabel());
    }
}
