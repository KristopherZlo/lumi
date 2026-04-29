package io.github.luma.client.selection;

import io.github.luma.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumiRegionSelectionStateTest {

    @Test
    void cornersModeBuildsBoundsFromTwoClicks() {
        LumiRegionSelectionState state = new LumiRegionSelectionState();

        state.selectPrimary(new BlockPoint(8, 70, 8));
        state.selectSecondary(new BlockPoint(2, 64, 4));

        var bounds = state.bounds().orElseThrow();
        assertEquals(new BlockPoint(2, 64, 4), bounds.min());
        assertEquals(new BlockPoint(8, 70, 8), bounds.max());
    }

    @Test
    void extendModeExpandsExistingBounds() {
        LumiRegionSelectionState state = new LumiRegionSelectionState();
        state.toggleMode();

        state.selectPrimary(new BlockPoint(8, 70, 8));
        state.selectSecondary(new BlockPoint(2, 64, 4));

        assertEquals(LumiRegionSelectionMode.EXTEND, state.mode());
        var bounds = state.bounds().orElseThrow();
        assertEquals(new BlockPoint(2, 64, 4), bounds.min());
        assertEquals(new BlockPoint(8, 70, 8), bounds.max());
    }

    @Test
    void emptySelectionHasNoBounds() {
        assertTrue(new LumiRegionSelectionState().bounds().isEmpty());
    }
}
