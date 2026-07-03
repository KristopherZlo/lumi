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
        assertTrue(undoRedoService.contains("withReplayContext(batches, replayContext)"));
        assertTrue(quickRollbackService.contains("withReplayContext(batches, replayContext)"));
        assertTrue(applier.contains("rememberReplayAction("));
    }
}
