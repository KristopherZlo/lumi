package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveActionJournalTest {
    private static final UUID PLAYER_A = new UUID(0, 1);
    private static final UUID PLAYER_B = new UUID(0, 2);
    private static final BlockPosition POSITION = new BlockPosition(3, 4, 5);

    @Test
    void keepsFirstOldAndLatestFinalForExactUndoRedo() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);
        journal.record(action, POSITION, block("stone"), block("dirt"));
        journal.record(action, POSITION, block("dirt"), block("gold_block"));
        assertTrue(journal.close(action));

        var undo = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(block("gold_block"), undo.expected().get(POSITION));
        assertEquals(block("stone"), undo.replacement().get(POSITION));
        journal.complete(undo);

        var redo = journal.prepareRedo(PLAYER_A).orElseThrow();
        assertEquals(block("stone"), redo.expected().get(POSITION));
        assertEquals(block("gold_block"), redo.replacement().get(POSITION));
    }

    @Test
    void refusesWholeUndoAfterNewerPlayerOverlapsAnyPosition() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID first = journal.begin(PLAYER_A);
        journal.record(first, POSITION, block("stone"), block("dirt"));
        journal.close(first);
        UUID newer = journal.begin(PLAYER_B);
        journal.record(newer, POSITION, block("dirt"), block("gold_block"));
        journal.close(newer);

        assertThrows(IllegalStateException.class, () -> journal.prepareUndo(PLAYER_A));
    }

    @Test
    void newDirectActionClearsOnlyThatPlayersRedo() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);
        journal.record(action, POSITION, block("stone"), block("dirt"));
        journal.close(action);
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());

        UUID next = journal.begin(PLAYER_A);
        journal.record(next, new BlockPosition(8, 9, 10), block("air"), block("stone"));
        journal.close(next);

        assertEquals(Optional.empty(), journal.prepareRedo(PLAYER_A));
    }

    private static BlockSnapshot block(String id) {
        return new BlockSnapshot("minecraft:" + id, Optional.empty());
    }
}
