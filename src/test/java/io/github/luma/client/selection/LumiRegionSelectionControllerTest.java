package io.github.luma.client.selection;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LumiRegionSelectionControllerTest {

    @Test
    void zoneSelectionHandlesMaximumCoordinateWithoutLoopOverflow() {
        Bounds3i bounds = new Bounds3i(
                new BlockPoint(Integer.MAX_VALUE, 0, 0),
                new BlockPoint(Integer.MAX_VALUE, 0, 0)
        );

        assertEquals(1, LumiRegionSelectionController.cellsIn(bounds).size());
    }

    @Test
    void zoneSelectionRejectsUnboundedCellAllocation() {
        Bounds3i bounds = new Bounds3i(
                new BlockPoint(0, 0, 0),
                new BlockPoint(65_536 * 16, 0, 0)
        );

        assertThrows(IllegalArgumentException.class, () -> LumiRegionSelectionController.cellsIn(bounds));
    }
}
