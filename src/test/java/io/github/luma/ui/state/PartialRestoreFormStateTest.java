package io.github.luma.ui.state;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PartialRestoreFormStateTest {

    @Test
    void useBoundsFillsFormAndMarksLumiRegionSource() {
        PartialRestoreFormState form = new PartialRestoreFormState();

        form.useBounds(
                new Bounds3i(new BlockPoint(8, 70, 8), new BlockPoint(2, 64, 4)),
                PartialRestoreRegionSource.LUMI_REGION
        );

        assertEquals("2", form.minX());
        assertEquals("64", form.minY());
        assertEquals("4", form.minZ());
        assertEquals("8", form.maxX());
        assertEquals("70", form.maxY());
        assertEquals("8", form.maxZ());
        assertEquals(PartialRestoreRegionSource.LUMI_REGION, form.request("Tower", "v0001", "tester").orElseThrow().regionSource());
    }

    @Test
    void manualEditReturnsRegionSourceToManualBounds() {
        PartialRestoreFormState form = new PartialRestoreFormState();
        form.useBounds(
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(1, 65, 1)),
                PartialRestoreRegionSource.LUMI_REGION
        );

        form.setMaxX("3");

        assertEquals(PartialRestoreRegionSource.MANUAL_BOUNDS, form.request("Tower", "v0001", "tester").orElseThrow().regionSource());
    }

    @Test
    void requestIncludesSelectedPartialRestoreMode() {
        PartialRestoreFormState form = new PartialRestoreFormState();
        form.useBounds(
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(1, 65, 1)),
                PartialRestoreRegionSource.LUMI_REGION
        );

        form.setRestoreMode(PartialRestoreMode.OUTSIDE_SELECTED_AREA);

        assertEquals(PartialRestoreMode.OUTSIDE_SELECTED_AREA, form.request("Tower", "v0001", "tester").orElseThrow().restoreMode());
    }
}
