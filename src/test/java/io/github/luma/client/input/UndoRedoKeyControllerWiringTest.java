package io.github.luma.client.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UndoRedoKeyControllerWiringTest {

    @Test
    void shortcutsApplyLumiHistoryForTheLocalPlayer() throws Exception {
        String source = Files.readString(
                Path.of("src/client/java/io/github/luma/client/input/UndoRedoKeyController.java")
        );

        assertTrue(source.contains("client.player.getName().getString()"));
        assertTrue(source.contains("this.undoRedo.undo(level, project.name(), actor)"));
        assertTrue(source.contains("this.undoRedo.redo(level, project.name(), actor)"));
    }

    @Test
    void externalToolActionsUseTheSameLumiReplayPath() throws Exception {
        String source = Files.readString(
                Path.of("src/client/java/io/github/luma/client/input/UndoRedoKeyController.java")
        );

        assertFalse(source.contains("performPrefixedCommand"));
        assertFalse(source.contains("AxiomUndoRedoBridge"));
        assertFalse(source.contains("ExternalUndoRedoPolicy"));
    }
}
