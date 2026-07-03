package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.UndoRedoActionStack;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void projectRevisionAdvancesWhenSecondActorStackChanges() {
        UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
        String projectId = "project-revision-second-actor-test";
        historyManager.clearProject(projectId);

        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "alex-action",
                "Alex",
                List.of(change(1)),
                List.of(),
                Instant.parse("2026-04-23T08:00:00Z")
        );
        long revision = historyManager.revision(projectId);
        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "steve-action",
                "Steve",
                List.of(change(2)),
                List.of(),
                Instant.parse("2026-04-23T08:00:01Z")
        );

        assertTrue(historyManager.revision(projectId) > revision);
    }

    @Test
    void playerScopedSelectionDoesNotReturnOtherPlayerActions() {
        UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
        String projectId = "player-scoped-selection-test";
        historyManager.clearProject(projectId);

        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "alex-action",
                "Alex",
                List.of(change(1)),
                List.of(),
                Instant.parse("2026-04-23T08:00:00Z")
        );
        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "steve-action",
                "Steve",
                List.of(change(2)),
                List.of(),
                Instant.parse("2026-04-23T08:00:01Z")
        );

        assertEquals("alex-action", historyManager.selectUndo(projectId, "Alex").action().id());
        assertEquals("steve-action", historyManager.selectUndo(projectId, "Steve").action().id());
        assertNull(historyManager.selectUndo(projectId, "Herobrine"));
    }

    @Test
    void relatedChangeWithBlankActorDoesNotJoinAnotherPlayersLatestAction() {
        UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
        String projectId = "related-change-no-actor-test";
        historyManager.clearProject(projectId);

        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "alex-action",
                "Alex",
                List.of(change(1)),
                List.of(),
                Instant.parse("2026-04-23T08:00:00Z")
        );
        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "steve-action",
                "Steve",
                List.of(change(2)),
                List.of(),
                Instant.parse("2026-04-23T08:00:01Z")
        );

        historyManager.recordRelatedChange(
                projectId,
                "minecraft:overworld",
                "",
                change(3),
                Instant.parse("2026-04-23T08:00:02Z"),
                Duration.ofSeconds(10),
                2
        );

        assertEquals(1, historyManager.selectUndo(projectId, "Alex").action().size());
        assertEquals(1, historyManager.selectUndo(projectId, "Steve").action().size());
    }

    @Test
    void relatedChangeWithActorJoinsThatActorsLatestActionOnly() {
        UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
        String projectId = "related-change-actor-test";
        historyManager.clearProject(projectId);

        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "alex-action",
                "Alex",
                List.of(change(1)),
                List.of(),
                Instant.parse("2026-04-23T08:00:00Z")
        );
        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "steve-action",
                "Steve",
                List.of(change(2)),
                List.of(),
                Instant.parse("2026-04-23T08:00:01Z")
        );

        historyManager.recordRelatedChange(
                projectId,
                "minecraft:overworld",
                "Alex",
                change(3),
                Instant.parse("2026-04-23T08:00:02Z"),
                Duration.ofSeconds(10),
                2
        );

        assertEquals(2, historyManager.selectUndo(projectId, "Alex").action().size());
        assertEquals(1, historyManager.selectUndo(projectId, "Steve").action().size());
    }

    @Test
    void laterPlayerEditBlocksStaleUndoForSameBlock() {
        UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
        String projectId = "player-conflict-ledger-test";
        historyManager.clearProject(projectId);

        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "alex-action",
                "Alex",
                List.of(change(1)),
                List.of(),
                Instant.parse("2026-04-23T08:00:00Z")
        );
        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "steve-action",
                "Steve",
                List.of(change(1)),
                List.of(),
                Instant.parse("2026-04-23T08:00:01Z")
        );

        assertNull(historyManager.selectUndo(projectId, "Alex"));
        assertEquals("steve-action", historyManager.selectUndo(projectId, "Steve").action().id());
    }

    @Test
    void undoingLaterPlayerEditRevealsPreviousOwnerForSameBlock() {
        UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
        String projectId = "player-conflict-ledger-undo-test";
        historyManager.clearProject(projectId);

        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "alex-action",
                "Alex",
                List.of(change(1)),
                List.of(),
                Instant.parse("2026-04-23T08:00:00Z")
        );
        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "steve-action",
                "Steve",
                List.of(change(1)),
                List.of(),
                Instant.parse("2026-04-23T08:00:01Z")
        );

        UndoRedoActionStack.Selection steveUndo = historyManager.selectUndo(projectId, "Steve");
        historyManager.completeUndo(projectId, steveUndo);

        assertEquals("alex-action", historyManager.selectUndo(projectId, "Alex").action().id());
        assertEquals("steve-action", historyManager.selectRedo(projectId, "Steve").action().id());
    }

    @Test
    void laterEditAfterUndoBlocksRedoForSameBlock() {
        UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
        String projectId = "player-conflict-ledger-redo-test";
        historyManager.clearProject(projectId);

        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "alex-action",
                "Alex",
                List.of(change(1)),
                List.of(),
                Instant.parse("2026-04-23T08:00:00Z")
        );
        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "steve-action",
                "Steve",
                List.of(change(1)),
                List.of(),
                Instant.parse("2026-04-23T08:00:01Z")
        );

        historyManager.completeUndo(projectId, historyManager.selectUndo(projectId, "Steve"));
        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "alex-followup",
                "Alex",
                List.of(change(1)),
                List.of(),
                Instant.parse("2026-04-23T08:00:02Z")
        );

        assertEquals("alex-followup", historyManager.selectUndo(projectId, "Alex").action().id());
        assertNull(historyManager.selectRedo(projectId, "Steve"));
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
