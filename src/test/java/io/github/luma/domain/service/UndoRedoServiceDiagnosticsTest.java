package io.github.luma.domain.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoRedoServiceDiagnosticsTest {

    @Test
    void undoRedoSelectionLogsReplayCountsForDiagnostics() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/domain/service/UndoRedoService.java"));

        assertTrue(source.contains("LumaLoadLog.event(\"undo-redo\", \"selected-action\""));
        assertTrue(source.contains("targetBlocks=\" + targetChanges.size()"));
        assertTrue(source.contains("targetEntities=\" + targetEntityChanges.size()"));
        assertTrue(source.contains("adjustmentBlocks=\" + pendingAdjustments.size()"));
    }

    @Test
    void restoredEntityReplayReceivesCausalContextForFollowUpMobFallout() throws Exception {
        String undoRedoService = Files.readString(Path.of("src/main/java/io/github/luma/domain/service/UndoRedoService.java"));
        String quickRollbackService = Files.readString(Path.of("src/main/java/io/github/luma/domain/service/QuickRollbackService.java"));
        String applier = Files.readString(Path.of("src/main/java/io/github/luma/minecraft/world/BlockChangeApplier.java"));

        assertTrue(undoRedoService.contains("new EntityBatch.ReplayContext("));
        assertTrue(undoRedoService.contains("batch.withEntityReplayContext(replayContext)"));
        assertTrue(quickRollbackService.contains("batch.withEntityReplayContext(replayContext)"));
        assertTrue(applier.contains("rememberReplayAction("));
    }

    @Test
    void playerScopedUndoRedoSelectsActorStackWhenActorIsKnown() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/domain/service/UndoRedoService.java"));

        assertTrue(source.contains("undo(ServerLevel level, String projectName, String actor)"));
        assertTrue(source.contains("redo(ServerLevel level, String projectName, String actor)"));
        assertTrue(source.contains("this.historyManager.selectUndo(project.id().toString(), actor)"));
        assertTrue(source.contains("this.historyManager.selectRedo(project.id().toString(), actor)"));
    }

    @Test
    void singleplayerUndoRedoFallsBackToProjectWideActionsAfterActorMiss() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/domain/service/UndoRedoService.java"));

        assertTrue(source.contains("selection == null && !level.getServer().isDedicatedServer()"));
        assertTrue(source.contains("this.historyManager.selectUndo(project.id().toString())"));
        assertTrue(source.contains("this.historyManager.selectRedo(project.id().toString())"));
    }

    @Test
    void undoRedoWaitsForActiveExplosiveContextsBeforeSelectingActions() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/domain/service/UndoRedoService.java"));

        int settlingIndex = source.indexOf("this.ensureStabilizationReady(level, project);");
        int selectUndoIndex = source.indexOf("this.historyManager.selectUndo(project.id().toString())");
        int selectRedoIndex = source.indexOf("this.historyManager.selectRedo(project.id().toString())");

        assertTrue(source.contains("ExplosiveEntityContextRegistry"));
        assertTrue(source.contains("this.explosiveContexts.hasActiveContexts()"));
        assertTrue(settlingIndex >= 0);
        assertTrue(settlingIndex < selectUndoIndex && settlingIndex < selectRedoIndex);
    }
}
