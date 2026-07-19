package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BlockPosition;
import java.util.UUID;
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
        assertEquals(new BlockBox(8, 9, 10, 8, 9, 10),
                selection.bounds().orElseThrow());
        assertTrue(selection.redo());
        assertEquals(new BlockBox(2, 3, 4, 8, 9, 10),
                selection.bounds().orElseThrow());

        selection.setSecond(new BlockPosition(1, 1, 1));
        assertFalse(selection.redo());
        selection.reset();
        assertTrue(selection.bounds().isEmpty());
        assertFalse(selection.undo());
    }

    @Test
    void retainsIndependentSelectionsForAtMostThirtyTwoScopes() {
        ClientSelection selection = new ClientSelection();
        UUID first = new UUID(0, 1);
        selection.activate(first, "minecraft:overworld");
        selection.setFirst(new BlockPosition(1, 2, 3));
        for (int index = 2; index <= ClientSelection.MAX_SCOPES + 1; index++) {
            selection.activate(new UUID(0, index), "minecraft:overworld");
            selection.setFirst(new BlockPosition(index, 0, 0));
        }
        assertEquals(ClientSelection.MAX_SCOPES, selection.retainedScopes());

        selection.activate(first, "minecraft:overworld");
        assertTrue(selection.bounds().isEmpty());
        selection.activate(new UUID(0, ClientSelection.MAX_SCOPES + 1),
                "minecraft:overworld");
        assertEquals(new BlockBox(
                        ClientSelection.MAX_SCOPES + 1, 0, 0,
                        ClientSelection.MAX_SCOPES + 1, 0, 0),
                selection.bounds().orElseThrow());
    }
}
