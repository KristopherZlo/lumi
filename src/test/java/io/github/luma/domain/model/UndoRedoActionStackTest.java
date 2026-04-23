package io.github.luma.domain.model;

import java.time.Instant;
import net.minecraft.nbt.CompoundTag;
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
}
