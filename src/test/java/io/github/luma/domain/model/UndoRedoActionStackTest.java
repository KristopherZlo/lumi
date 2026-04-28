package io.github.luma.domain.model;

import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoRedoActionStackTest {

    private static final Instant NOW = Instant.parse("2026-04-23T08:00:00Z");

    @Test
    void groupsRepeatedPositionsInsideOneAction() {
        UndoRedoActionStack stack = new UndoRedoActionStack();

        stack.recordChange("action-1", "Alex", "project", "minecraft:overworld", change(1, "minecraft:stone", "minecraft:dirt"), NOW);
        stack.recordChange("action-1", "Alex", "project", "minecraft:overworld", change(1, "minecraft:dirt", "minecraft:gold_block"), NOW);

        UndoRedoActionStack.Selection selection = stack.selectUndo();
        assertNotNull(selection);
        assertEquals(1, selection.action().size());
        StoredBlockChange change = selection.action().undoChanges().getFirst();
        assertEquals("minecraft:stone", change.oldValue().blockId());
        assertEquals("minecraft:gold_block", change.newValue().blockId());
    }

    @Test
    void undoAndRedoMoveActionsBetweenStacks() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        stack.recordChange("action-1", "Alex", "project", "minecraft:overworld", change(1, "minecraft:stone", "minecraft:dirt"), NOW);

        UndoRedoActionStack.Selection undo = stack.selectUndo();
        stack.completeUndo(undo);

        assertFalse(stack.canUndo());
        assertTrue(stack.canRedo());
        assertEquals(List.of("action-1"), stack.recentRedoActions(1).stream().map(UndoRedoAction::id).toList());

        UndoRedoActionStack.Selection redo = stack.selectRedo();
        stack.completeRedo(redo);

        assertTrue(stack.canUndo());
        assertFalse(stack.canRedo());
    }

    @Test
    void newActionClearsRedoStack() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        stack.recordChange("action-1", "Alex", "project", "minecraft:overworld", change(1, "minecraft:stone", "minecraft:dirt"), NOW);
        stack.completeUndo(stack.selectUndo());

        stack.recordChange("action-2", "Alex", "project", "minecraft:overworld", change(2, "minecraft:air", "minecraft:oak_planks"), NOW);

        assertFalse(stack.canRedo());
        assertEquals("action-2", stack.recentUndoActions(1).getFirst().id());
    }

    @Test
    void relatedChangesJoinLatestActionInsideJoinWindow() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        stack.recordChange("action-1", "Alex", "project", "minecraft:overworld", change(1, "minecraft:stone", "minecraft:dirt"), NOW);

        stack.recordRelatedChange(
                "minecraft:overworld",
                change(18, "minecraft:air", "minecraft:water"),
                NOW.plusSeconds(5),
                java.time.Duration.ofSeconds(10),
                2
        );

        UndoRedoActionStack.Selection selection = stack.selectUndo();
        assertNotNull(selection);
        assertEquals("action-1", selection.action().id());
        assertEquals(2, selection.action().size());
    }

    @Test
    void staleSelectionDoesNotDropUndoHistory() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        stack.recordChange("action-1", "Alex", "project", "minecraft:overworld", change(1, "minecraft:stone", "minecraft:dirt"), NOW);

        UndoRedoActionStack.Selection staleSelection = stack.selectUndo();
        stack.recordChange("action-2", "Alex", "project", "minecraft:overworld", change(2, "minecraft:air", "minecraft:oak_planks"), NOW);
        stack.completeUndo(staleSelection);

        assertTrue(stack.canUndo());
        assertFalse(stack.canRedo());
        assertEquals(List.of("action-2", "action-1"), stack.recentUndoActions(2).stream().map(UndoRedoAction::id).toList());
    }

    @Test
    void entityChangesParticipateInUndoRedoActions() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        String entityId = "00000000-0000-0000-0000-000000000010";

        stack.recordEntityChange(
                "action-entity",
                "Axiom",
                "project",
                "minecraft:overworld",
                new StoredEntityChange(
                        entityId,
                        "minecraft:block_display",
                        entity("minecraft:block_display", entityId, 1.0D),
                        entity("minecraft:block_display", entityId, 2.0D)
                ),
                NOW
        );

        UndoRedoActionStack.Selection selection = stack.selectUndo();
        assertNotNull(selection);
        assertEquals(1, selection.action().size());
        StoredEntityChange undo = selection.action().undoEntityChanges().getFirst();
        assertEquals(entityId, undo.entityId());
        assertEquals(2.0D, undo.newValue().entityTag().getListOrEmpty("Pos").getDoubleOr(0, 0.0D));
        assertEquals(1.0D, selection.action().inverseEntityChanges().getFirst().newValue()
                .entityTag().getListOrEmpty("Pos").getDoubleOr(0, 0.0D));
    }

    private static StoredBlockChange change(int x, String oldBlock, String newBlock) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, 1),
                new StatePayload(state(oldBlock), null),
                new StatePayload(state(newBlock), null)
        );
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }

    private static EntityPayload entity(String type, String uuid, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", type);
        tag.putString("UUID", uuid);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }
}
