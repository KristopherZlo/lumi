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
}
