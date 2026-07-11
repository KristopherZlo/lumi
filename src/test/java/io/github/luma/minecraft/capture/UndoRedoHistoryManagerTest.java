package io.github.luma.minecraft.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.UndoRedoActionStack;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UndoRedoHistoryManagerTest {

    private static final String PROJECT = "undo-manager-test";
    private final UndoRedoHistoryManager manager = UndoRedoHistoryManager.getInstance();

    @AfterEach
    void clear() {
        this.manager.clearProject(PROJECT);
    }

    @Test
    void keepsOneGlobalOrderAcrossNativeAndExternalActions() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        this.record("native", "Steve", 0, now);
        this.record("axiom", "axiom:Steve", 1, now.plusSeconds(1));

        UndoRedoActionStack.Selection latest = this.manager.selectUndo(PROJECT, "Steve");
        assertNotNull(latest);
        assertEquals("axiom", latest.action().id());
        assertTrue(this.manager.completeUndo(PROJECT, latest));
        assertEquals("axiom", this.manager.selectRedo(PROJECT, "Steve").action().id());
    }

    @Test
    void refusesAnotherActorsLatestAction() {
        this.record("other", "Alex", 0, Instant.parse("2026-01-01T00:00:00Z"));

        assertNull(this.manager.selectUndo(PROJECT, "Steve"));
        assertNotNull(this.manager.selectUndo(PROJECT, "Alex"));
    }

    @Test
    void exposesRevisionedImmutableSnapshots() {
        this.record("native", "Steve", 0, Instant.parse("2026-01-01T00:00:00Z"));

        UndoRedoHistoryManager.RecentActionsSnapshot snapshot =
                this.manager.recentUndoActionsSnapshot(PROJECT, 10);
        assertEquals(1L, snapshot.revision());
        assertEquals(1, snapshot.actions().size());
    }

    private void record(String actionId, String actor, int x, Instant now) {
        this.manager.recordAction(
                PROJECT,
                "minecraft:overworld",
                actionId,
                actor,
                List.of(new StoredBlockChange(
                        new BlockPoint(x, 64, 0),
                        payload("air"),
                        payload("stone")
                )),
                List.of(),
                now
        );
    }

    private static StatePayload payload(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", "minecraft:" + blockId);
        return new StatePayload(tag, null);
    }
}
