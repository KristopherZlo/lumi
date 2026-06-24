package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.WorkZoneCell;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkZoneBoundsMergerTest {

    private final WorkZoneBoundsMerger merger = new WorkZoneBoundsMerger();

    @Test
    void mergesSolidAdjacentCellsIntoOneBox() {
        List<Bounds3i> boxes = this.merger.merge(List.of(
                new WorkZoneCell(0, 0, 0),
                new WorkZoneCell(1, 0, 0),
                new WorkZoneCell(0, 1, 0),
                new WorkZoneCell(1, 1, 0)
        ));

        assertEquals(List.of(new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(31, 31, 15))), boxes);
    }

    @Test
    void keepsNonRectangularRemainderAsSeparateBox() {
        List<Bounds3i> boxes = this.merger.merge(List.of(
                new WorkZoneCell(0, 0, 0),
                new WorkZoneCell(1, 0, 0),
                new WorkZoneCell(0, 0, 1)
        ));

        assertEquals(List.of(
                new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(31, 15, 15)),
                new Bounds3i(new BlockPoint(0, 0, 16), new BlockPoint(15, 15, 31))
        ), boxes);
    }
}
