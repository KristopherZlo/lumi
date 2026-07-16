package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BlockPosition;
import org.junit.jupiter.api.Test;

class ClientSelectionTest {
    @Test
    void buildsNormalizedBoundsAndSupportsExactUndoRedo() {
        ClientSelection selection = new ClientSelection();
        selection.setFirst(new BlockPosition(8, 9, 10));
        selection.setSecond(new BlockPosition(2, 3, 4));

        assertEquals(new BlockBox(2, 3, 4, 8, 9, 10),
                selection.bounds().orElseThrow());
        assertTrue(selection.undo());
        assertTrue(selection.bounds().isEmpty());
        assertTrue(selection.redo());
        assertEquals(new BlockBox(2, 3, 4, 8, 9, 10),
                selection.bounds().orElseThrow());

        selection.setSecond(new BlockPosition(1, 1, 1));
        assertFalse(selection.redo());
        selection.reset();
        assertTrue(selection.bounds().isEmpty());
        assertFalse(selection.undo());
    }
}
