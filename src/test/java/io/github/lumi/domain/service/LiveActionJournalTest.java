package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityState;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveActionJournalTest {
    private static final UUID PLAYER_A = new UUID(0, 1);
    private static final UUID PLAYER_B = new UUID(0, 2);
    private static final UUID ENTITY = new UUID(0, 3);
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
    void exposesPlayerOwnershipForCausalConsequences() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);

        assertEquals(Optional.of(PLAYER_A), journal.owner(action));
        assertEquals(Optional.empty(), journal.owner(UUID.randomUUID()));
    }

    @Test
    void summarizesOneActionWithoutExposingWorldContent() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);
        journal.record(action, POSITION, block("stone"), block("dirt"));
        journal.retain(action);

        var summary = journal.summary(action);

        assertEquals(action, summary.actionId());
        assertEquals(PLAYER_A, summary.player());
        assertEquals(1, summary.blocks());
        assertEquals(0, summary.entities());
        assertEquals(1, summary.delayedReferences());
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
    void undoRedoTraversesOverlappingAppliedActions() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID placement = journal.begin(PLAYER_A);
        journal.record(placement, POSITION, block("air"), block("tnt"));
        journal.recordEntity(
                placement, ENTITY, Optional.empty(), Optional.of(entity(1)));
        journal.close(placement);
        UUID explosion = journal.begin(PLAYER_A);
        journal.record(explosion, POSITION, block("tnt"), block("air"));
        journal.recordEntity(
                explosion, ENTITY, Optional.of(entity(1)), Optional.of(entity(2)));
        journal.close(explosion);

        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());
        assertEquals(placement, journal.prepareUndo(PLAYER_A).orElseThrow().actionId());
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());
        journal.complete(journal.prepareRedo(PLAYER_A).orElseThrow());
        assertEquals(explosion, journal.prepareRedo(PLAYER_A).orElseThrow().actionId());
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

    @Test
    void clearingRedoKeepsOlderUnrelatedUndoAvailable() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID first = add(journal, 1);
        add(journal, 2);
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());
        add(journal, 3);
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());

        assertEquals(first, journal.prepareUndo(PLAYER_A).orElseThrow().actionId());
    }

    @Test
    void emptyNewerActionDoesNotInvalidateOlderUndo() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID older = add(journal, 1);
        UUID empty = journal.begin(PLAYER_A);

        assertTrue(!journal.close(empty));
        assertEquals(older, journal.prepareUndo(PLAYER_A).orElseThrow().actionId());
    }

    @Test
    void oversizedActionIsUnavailableWithAReason() {
        LiveActionJournal journal = new LiveActionJournal(
                new LiveActionJournal.Limits(64, 32, 128, 256));
        UUID action = journal.begin(PLAYER_A);

        journal.record(action, POSITION, block("stone"), block("gold_block"));

        assertTrue(!journal.close(action));
        assertTrue(journal.lastUnavailableReason(PLAYER_A).orElseThrow().contains("limit"));
        assertEquals(Optional.empty(), journal.prepareUndo(PLAYER_A));
    }

    @Test
    void externalCaptureLimitMakesOpenActionUnavailable() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);

        journal.makeUnavailable(action, "Axiom edit exceeded its capture limit");

        assertTrue(!journal.close(action));
        assertTrue(journal.lastUnavailableReason(PLAYER_A).orElseThrow().contains("Axiom"));
        assertEquals(Optional.empty(), journal.prepareUndo(PLAYER_A));
    }

    @Test
    void evictsOldestClosedActionAtPlayerCountLimit() {
        LiveActionJournal journal = new LiveActionJournal(
                new LiveActionJournal.Limits(2, 1_000, 4_000, 8_000));
        UUID first = add(journal, 1);
        UUID second = add(journal, 2);
        UUID third = add(journal, 3);

        var newest = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(third, newest.actionId());
        journal.complete(newest);
        var remaining = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(second, remaining.actionId());
        assertTrue(!remaining.actionId().equals(first));
    }

    @Test
    void dimensionByteLimitEvictsOldestClosedPlayerAction() {
        LiveActionJournal journal = new LiveActionJournal(
                new LiveActionJournal.Limits(64, 1_000, 1_000, 80));
        add(journal, 1);
        UUID newer = journal.begin(PLAYER_B);
        journal.record(newer, new BlockPosition(2, 0, 0), block("air"), block("stone"));
        journal.close(newer);

        assertEquals(Optional.empty(), journal.prepareUndo(PLAYER_A));
        assertEquals(newer, journal.prepareUndo(PLAYER_B).orElseThrow().actionId());
    }

    @Test
    void causalReferenceKeepsEmptyClosedActionUntilReleased() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);
        journal.retain(action);

        assertTrue(journal.close(action));
        assertTrue(journal.prepareUndo(PLAYER_A).isPresent());
        journal.release(action);

        assertEquals(Optional.empty(), journal.prepareUndo(PLAYER_A));
    }

    @Test
    void delayedChangeSurvivesAfterCausalReferenceReleases() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);
        journal.retain(action);
        journal.close(action);
        journal.record(action, POSITION, block("air"), block("stone"));
        journal.release(action);

        assertEquals(block("stone"),
                journal.prepareUndo(PLAYER_A).orElseThrow().expected().get(POSITION));
    }

    @Test
    void keepsFirstOldAndLatestFinalEntityForExactUndoRedo() {
        LiveActionJournal journal = new LiveActionJournal();
        EntityState first = entity(1);
        EntityState latest = entity(2);
        UUID action = journal.begin(PLAYER_A);
        journal.recordEntity(action, ENTITY, Optional.empty(), Optional.of(first));
        journal.recordEntity(action, ENTITY, Optional.of(first), Optional.of(latest));
        assertTrue(journal.close(action));

        var undo = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(Optional.of(latest), undo.expectedEntities().get(ENTITY));
        assertEquals(Optional.empty(), undo.replacementEntities().get(ENTITY));
        journal.complete(undo);

        var redo = journal.prepareRedo(PLAYER_A).orElseThrow();
        assertEquals(Optional.empty(), redo.expectedEntities().get(ENTITY));
        assertEquals(Optional.of(latest), redo.replacementEntities().get(ENTITY));
    }

    @Test
    void refusesWholeUndoAfterNewerPlayerOverlapsEntity() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID first = journal.begin(PLAYER_A);
        journal.recordEntity(first, ENTITY, Optional.empty(), Optional.of(entity(1)));
        journal.close(first);
        UUID newer = journal.begin(PLAYER_B);
        journal.recordEntity(newer, ENTITY, Optional.of(entity(1)), Optional.of(entity(2)));
        journal.close(newer);

        assertThrows(IllegalStateException.class, () -> journal.prepareUndo(PLAYER_A));
    }

    private static UUID add(LiveActionJournal journal, int x) {
        UUID action = journal.begin(PLAYER_A);
        journal.record(action, new BlockPosition(x, 0, 0), block("air"), block("stone"));
        journal.close(action);
        return action;
    }

    private static BlockSnapshot block(String id) {
        return new BlockSnapshot("minecraft:" + id, Optional.empty());
    }

    private static EntityState entity(int state) {
        return new EntityState(
                ENTITY, "minecraft:armor_stand",
                new CanonicalNbt(new byte[] {(byte) state}));
    }
}
