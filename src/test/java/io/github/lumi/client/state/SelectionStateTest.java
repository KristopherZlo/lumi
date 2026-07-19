package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BlockPosition;
import org.junit.jupiter.api.Test;

class SelectionStateTest {
    private final SelectionState selection = new SelectionState();

    @Test
    void oneCornerImmediatelyCreatesAOneBlockSelection() {
        selection.selectPrimary(point(4, -2, 9));

        assertEquals(new BlockBox(4, -2, 9, 4, -2, 9),
                selection.bounds().orElseThrow());
    }

    @Test
    void cornersNormalizeAndExtendGrowsOrResetsTheBox() {
        selection.selectPrimary(point(5, 8, 3));
        selection.selectSecondary(point(1, 2, -4));
        assertEquals(new BlockBox(1, 2, -4, 5, 8, 3),
                selection.bounds().orElseThrow());

        selection.toggleMode();
        assertEquals(SelectionMode.EXTEND, selection.mode());
        selection.selectPrimary(point(9, 0, 2));
        assertEquals(new BlockBox(1, 0, -4, 9, 8, 3),
                selection.bounds().orElseThrow());
        selection.selectSecondary(point(-7, 6, 11));
        assertEquals(new BlockBox(-7, 6, 11, -7, 6, 11),
                selection.bounds().orElseThrow());
    }

    @Test
    void resizeAndModeParticipateInUndoRedo() {
        selection.selectPrimary(point(0, 0, 0));
        selection.selectSecondary(point(2, 2, 2));
        selection.resize(SelectionSide.MIN_X, 1);
        assertEquals(new BlockBox(-1, 0, 0, 2, 2, 2),
                selection.bounds().orElseThrow());

        assertTrue(selection.undo());
        assertEquals(new BlockBox(0, 0, 0, 2, 2, 2),
                selection.bounds().orElseThrow());
        assertTrue(selection.redo());
        assertEquals(new BlockBox(-1, 0, 0, 2, 2, 2),
                selection.bounds().orElseThrow());
    }

    private static BlockPosition point(int x, int y, int z) {
        return new BlockPosition(x, y, z);
    }
}
