package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.service.LiveActionJournal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AxiomBufferActionGrouperTest {
    @Test void explicitNativeIdentityJoinsDispatchesAndLatePacketsCannotAppendToAnUndoneEntry() {
        UUID entry = UUID.randomUUID();
        var identity = Optional.of(new io.github.lumi.domain.model.AxiomEntryIdentity(UUID.randomUUID(), entry));
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        groups.begin(connection, ACTOR, first, identity, false);
        assertTrue(groups.admits(connection, ACTOR, first));
        packet(connection, ACTOR, first, "stone", "dirt");
        groups.complete(connection, first);
        groups.begin(connection, ACTOR, second, identity, false);
        packet(connection, ACTOR, second, "dirt", "gold_block");
        groups.complete(connection, second);
        assertEquals(1, journal.view(ACTOR).undo().size());
        journal.completeSelected(journal.prepareAxiom(ACTOR, entry, LiveActionJournal.Direction.UNDO).orElseThrow());
        UUID stale = UUID.randomUUID();
        groups.begin(connection, ACTOR, stale, identity, false);
        assertFalse(groups.admits(connection, ACTOR, stale));
        assertFalse(groups.admits(new Object(), ACTOR, stale));
        groups.complete(connection, stale);
        assertFalse(groups.admits(connection, ACTOR, stale));
    }
    private static final UUID ACTOR = new UUID(0, 1);
    private static final BlockPosition POSITION = new BlockPosition(0, 64, 0);
    private final LiveActionJournal journal = new LiveActionJournal();
    private final AxiomBufferActionGrouper groups = new AxiomBufferActionGrouper(journal);
    private final Object connection = new Object();

    @Test void allPacketsShareOneUndoAndReconnectCannotCompleteTheOldConnection() {
        UUID dispatch = UUID.randomUUID();
        groups.begin(connection, ACTOR, dispatch);
        packet(connection, ACTOR, dispatch, "stone", "dirt");
        assertThrows(IllegalStateException.class, () -> journal.prepareUndo(ACTOR));
        packet(connection, ACTOR, dispatch, "dirt", "gold_block");
        groups.complete(new Object(), dispatch);
        assertThrows(IllegalStateException.class, () -> journal.prepareUndo(ACTOR));
        groups.complete(connection, dispatch);
        var undo = journal.prepareUndo(ACTOR).orElseThrow();
        assertEquals(block("stone"), undo.replacement().get(POSITION));
        assertEquals(block("gold_block"), undo.expected().get(POSITION));
        journal.complete(undo);
        assertTrue(journal.prepareUndo(ACTOR).isEmpty());
        assertEquals(0, groups.pendingCount());
    }

    @Test void overlappingBuilderStillPreventsUndoOfTheWholeDispatch() {
        UUID dispatch = UUID.randomUUID();
        groups.begin(connection, ACTOR, dispatch);
        packet(connection, ACTOR, dispatch, "stone", "dirt");
        UUID other = new UUID(0, 2);
        UUID edit = journal.begin(other);
        journal.record(edit, POSITION, block("dirt"), block("diamond_block"));
        journal.close(edit);
        groups.complete(connection, dispatch);
        assertThrows(IllegalStateException.class, () -> journal.prepareUndo(ACTOR));
        assertTrue(journal.prepareUndo(other).isPresent());
    }

    @Test void overflowAndUnannouncedTailsNeverBecomePartialUndoActions() {
        for (int index = 0; index < AxiomBufferActionGrouper.MAX_PENDING; index++) {
            groups.begin(connection, ACTOR, UUID.randomUUID());
        }
        UUID overflow = UUID.randomUUID();
        groups.begin(connection, ACTOR, overflow);
        packet(connection, ACTOR, overflow, "stone", "dirt");
        assertEquals(AxiomBufferActionGrouper.MAX_PENDING, groups.pendingCount());
        assertTrue(journal.prepareUndo(ACTOR).isEmpty());
        groups.finishConnection(connection);
        assertEquals(0, groups.pendingCount());
        packet(connection, ACTOR, UUID.randomUUID(), "dirt", "gold_block");
        assertTrue(journal.prepareUndo(ACTOR).isEmpty());
    }

    @Test void captureFallbackDisablesEveryLaterPacketUntilTheDispatchEnds() {
        UUID dispatch = UUID.randomUUID();
        groups.begin(connection, ACTOR, dispatch);
        UUID first = journal.begin(ACTOR);
        groups.attach(connection, ACTOR, dispatch, first);
        journal.makeUnavailable(first, "capture limit");
        journal.close(first);
        packet(connection, ACTOR, dispatch, "stone", "dirt");
        groups.complete(connection, dispatch);
        assertFalse(journal.isAvailable(first));
        assertTrue(journal.prepareUndo(ACTOR).isEmpty());
    }

    private void packet(Object source, UUID actor, UUID dispatch, String before, String after) {
        UUID action = journal.begin(actor);
        groups.attach(source, actor, dispatch, action);
        journal.record(action, POSITION, block(before), block(after));
        journal.close(action);
    }

    private static BlockSnapshot block(String name) { return new BlockSnapshot("minecraft:" + name, Optional.empty()); }
}
