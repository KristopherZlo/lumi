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
    void extendPrimaryExpandsExistingBounds() {
        LumiRegionSelectionState state = new LumiRegionSelectionState();
        state.toggleMode();

        state.selectPrimary(new BlockPoint(8, 70, 8));
        state.selectPrimary(new BlockPoint(2, 64, 4));

        assertEquals(LumiRegionSelectionMode.EXTEND, state.mode());
        var bounds = state.bounds().orElseThrow();
        assertEquals(new BlockPoint(2, 64, 4), bounds.min());
        assertEquals(new BlockPoint(8, 70, 8), bounds.max());
    }

    @Test
    void extendSecondaryResetsSelectionToOneBlock() {
        LumiRegionSelectionState state = new LumiRegionSelectionState();
        state.toggleMode();
        state.selectPrimary(new BlockPoint(8, 70, 8));
        state.selectPrimary(new BlockPoint(2, 64, 4));

        state.selectSecondary(new BlockPoint(12, 72, 12));

        var bounds = state.bounds().orElseThrow();
        assertEquals(new BlockPoint(12, 72, 12), bounds.min());
        assertEquals(new BlockPoint(12, 72, 12), bounds.max());
    }

    @Test
    void resizeLookedSideGrowsAndShrinksSelectedFace() {
        LumiRegionSelectionState state = new LumiRegionSelectionState();
        state.selectPrimary(new BlockPoint(2, 64, 4));
        state.selectSecondary(new BlockPoint(8, 70, 8));

        state.resize(LumiRegionSelectionState.Side.MIN_X, 1);
        state.resize(LumiRegionSelectionState.Side.MAX_Y, -1);

        var bounds = state.bounds().orElseThrow();
        assertEquals(new BlockPoint(1, 64, 4), bounds.min());
        assertEquals(new BlockPoint(8, 69, 8), bounds.max());
    }

    @Test
    void resizeCannotInvertSelection() {
        LumiRegionSelectionState state = new LumiRegionSelectionState();
        state.selectPrimary(new BlockPoint(2, 64, 4));
        state.selectSecondary(new BlockPoint(2, 64, 4));

        state.resize(LumiRegionSelectionState.Side.MIN_X, -1);
        state.resize(LumiRegionSelectionState.Side.MAX_Y, -1);

        var bounds = state.bounds().orElseThrow();
        assertEquals(new BlockPoint(2, 64, 4), bounds.min());
        assertEquals(new BlockPoint(2, 64, 4), bounds.max());
    }

    @Test
    void clearRemovesSelectionBounds() {
        LumiRegionSelectionState state = new LumiRegionSelectionState();
        state.selectPrimary(new BlockPoint(8, 70, 8));

        state.clear();

        assertTrue(state.bounds().isEmpty());
    }

    @Test
    void selectionChangesCanBeUndoneAndRedone() {
        LumiRegionSelectionState state = new LumiRegionSelectionState();
        state.selectPrimary(new BlockPoint(2, 64, 4));
        state.selectSecondary(new BlockPoint(8, 70, 8));
        state.resize(LumiRegionSelectionState.Side.MAX_X, 1);

        assertTrue(state.undo());
        assertEquals(new BlockPoint(8, 70, 8), state.bounds().orElseThrow().max());

        assertTrue(state.redo());
        assertEquals(new BlockPoint(9, 70, 8), state.bounds().orElseThrow().max());
    }

    @Test
    void emptySelectionHasNoBounds() {
        assertTrue(new LumiRegionSelectionState().bounds().isEmpty());
    }
}
