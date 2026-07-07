package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.debug.LumaDiagnosticsLog;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.UndoRedoActionStack;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void recentUndoPreviewSnapshotKeepsFullActionForOverlay() {
        UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
        String projectId = "recent-preview-full-action-test";
        historyManager.clearProject(projectId);
        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "blast",
                "Alex",
                List.of(change(1), change(2), change(3)),
                List.of(),
                Instant.parse("2026-04-23T08:00:00Z")
        );

        UndoRedoHistoryManager.RecentActionsSnapshot snapshot =
                historyManager.recentUndoPreviewActionsSnapshot(projectId, 10);

        assertEquals(3, snapshot.actions().getFirst().size());
        assertEquals(3, historyManager.selectUndo(projectId, "Alex").action().size());
    }

    @Test
    void recentUndoPreviewFiltersConflictsBeforeCopyingActions() {
        UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
        String projectId = "recent-preview-conflict-copy-test";
        historyManager.clearProject(projectId);
        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "wide-action",
                "Alex",
                List.of(change(1), change(2)),
                List.of(),
                Instant.parse("2026-04-23T08:00:00Z")
        );
        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "later-conflict",
                "Steve",
                List.of(change(2)),
                List.of(),
                Instant.parse("2026-04-23T08:00:01Z")
        );

        UndoRedoHistoryManager.RecentActionsSnapshot snapshot =
                historyManager.recentUndoPreviewActionsSnapshot(projectId, 10);

        assertEquals(List.of("later-conflict"), snapshot.actions().stream().map(action -> action.id()).toList());
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
    void playerScopedSelectionIncludesOwnedExternalToolActions() {
        UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
        String projectId = "player-owned-tool-selection-test";
        historyManager.clearProject(projectId);

        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "alex-axiom-action",
                "axiom:Alex",
                List.of(change(1)),
                List.of(),
                Instant.parse("2026-04-23T08:00:00Z")
        );
        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "steve-axiom-action",
                "axiom:Steve",
                List.of(change(2)),
                List.of(),
                Instant.parse("2026-04-23T08:00:01Z")
        );

        assertEquals("alex-axiom-action", historyManager.selectUndo(projectId, "Alex").action().id());
        assertEquals("steve-axiom-action", historyManager.selectUndo(projectId, "Steve").action().id());
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
    void fluidUndoDiagnosticsLogRecordsSelectionAndCompletion() throws Exception {
        UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
        String projectId = "fluid-undo-diagnostics-test";
        Path logPath = Files.createTempFile("lumi-fluid-undo", ".log");
        String previousEnabled = System.getProperty("lumi.fluidUndoLog");
        String previousPath = System.getProperty("lumi.fluidUndoLog.path");
        try {
            System.setProperty("lumi.fluidUndoLog", "true");
            System.setProperty("lumi.fluidUndoLog.path", logPath.toString());
            historyManager.clearProject(projectId);

            historyManager.recordAction(
                    projectId,
                    "minecraft:overworld",
                    "water-tail",
                    "Alex",
                    List.of(change(3)),
                    List.of(),
                    Instant.parse("2026-04-23T08:00:00Z")
            );
            UndoRedoActionStack.Selection selection = historyManager.selectUndo(projectId, "Alex");
            historyManager.completeUndo(projectId, selection);
            LumaDiagnosticsLog.close();

            String log = Files.readString(logPath);
            assertTrue(log.contains("undo-action-root-recorded"));
            assertTrue(log.contains("undo-select"));
            assertTrue(log.contains("undo-complete"));
            assertTrue(log.contains("water-tail"));
        } finally {
            LumaDiagnosticsLog.close();
            restoreProperty("lumi.fluidUndoLog", previousEnabled);
            restoreProperty("lumi.fluidUndoLog.path", previousPath);
            historyManager.clearProject(projectId);
            Files.deleteIfExists(logPath);
        }
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

    @Test
    void hasRedoActionFindsActionsMovedByUndo() {
        UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
        String projectId = "has-redo-action-test";
        historyManager.clearProject(projectId);
        historyManager.recordAction(
                projectId,
                "minecraft:overworld",
                "blast",
                "Alex",
                List.of(change(1)),
                List.of(),
                Instant.parse("2026-04-23T08:00:00Z")
        );

        assertNull(historyManager.selectRedo(projectId, "Alex"));
        assertTrue(!historyManager.hasRedoAction(projectId, "blast"));

        historyManager.completeUndo(projectId, historyManager.selectUndo(projectId, "Alex"));

        assertTrue(historyManager.hasRedoAction(projectId, "blast"));
        historyManager.clearProject(projectId);
        assertTrue(!historyManager.hasRedoAction(projectId, "blast"));
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

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
            return;
        }
        System.setProperty(name, value);
    }
}
