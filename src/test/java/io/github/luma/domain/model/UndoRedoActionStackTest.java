package io.github.luma.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class UndoRedoActionStackTest {

    @Test
    void foldsImmediateAndDeferredChangesByActionId() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        Instant started = Instant.parse("2026-01-01T00:00:00Z");

        stack.recordAction("action", "player", "project", "overworld",
                List.of(change(0, "stone", "dirt")), List.of(), started);
        stack.recordCurrentCausalAction("action", "player", "project", "overworld",
                List.of(change(1, "air", "sand")), List.of(), started.plusSeconds(2));

        UndoRedoActionStack.Selection selection = stack.selectUndo();
        assertNotNull(selection);
        assertEquals("action", selection.action().id());
        assertEquals(2, selection.action().redoChanges().size());
        assertTrue(stack.completeUndo(selection));
        assertTrue(stack.canRedo());
    }

    @Test
    void ignoresLateFalloutAfterActionWasUndone() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        stack.recordAction("action", "player", "project", "overworld",
                List.of(change(0, "air", "stone")), List.of(), now);
        assertTrue(stack.completeUndo(stack.selectUndo()));
        long revision = stack.revision();

        stack.recordCurrentCausalAction("action", "player", "project", "overworld",
                List.of(change(1, "air", "sand")), List.of(), now.plusSeconds(1));

        assertEquals(revision, stack.revision());
        assertFalse(stack.canUndo());
        assertTrue(stack.canRedo());
    }

    @Test
    void rejectsCompletionWhenSelectedActionChanged() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        stack.recordAction("action", "player", "project", "overworld",
                List.of(change(0, "air", "stone")), List.of(), now);
        UndoRedoActionStack.Selection stale = stack.selectUndo();

        stack.recordCurrentCausalAction("action", "player", "project", "overworld",
                List.of(change(1, "air", "dirt")), List.of(), now.plusSeconds(1));

        assertFalse(stack.completeUndo(stale));
        assertTrue(stack.canUndo());
    }

    @Test
    void keepsOnlyConfiguredNumberOfActions() {
        UndoRedoActionStack stack = new UndoRedoActionStack(2);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        for (int index = 0; index < 3; index++) {
            stack.recordAction("action-" + index, "player", "project", "overworld",
                    List.of(change(index, "air", "stone")), List.of(), now.plusSeconds(index));
        }

        assertEquals(2, stack.recentUndoActions(10).size());
        assertNull(stack.recentUndoActions(10).stream()
                .filter(action -> action.id().equals("action-0"))
                .findFirst()
                .orElse(null));
    }

    private static StoredBlockChange change(int x, String before, String after) {
        return new StoredBlockChange(new BlockPoint(x, 64, 0), payload(before), payload(after));
    }

    private static StatePayload payload(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", "minecraft:" + blockId);
        return new StatePayload(tag, null);
    }
}
