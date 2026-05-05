package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoRedoHistoryManagerTest {

    @Test
    void recentUndoSnapshotKeepsRevisionAndActionCopiesStable() {
        UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
        String projectId = "recent-snapshot-test";
        historyManager.clearProject(projectId);
        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "large-action",
                "Alex",
                List.of(change(1)),
                List.of(),
                Instant.parse("2026-04-23T08:00:00Z")
        );

        UndoRedoHistoryManager.RecentActionsSnapshot snapshot = historyManager.recentUndoActionsSnapshot(projectId, 10);
        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "tiny-action",
                "Alex",
                List.of(change(2)),
                List.of(),
                Instant.parse("2026-04-23T08:00:01Z")
        );

        assertTrue(historyManager.revision(projectId) > snapshot.revision());
        assertEquals(List.of("large-action"), snapshot.actions().stream().map(action -> action.id()).toList());
    }

    private static StoredBlockChange change(int x) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, 1),
                new StatePayload(state("minecraft:stone"), null),
                new StatePayload(state("minecraft:glass"), null)
        );
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }
}
