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
        assertTrue(source.contains("this.undoRedo.undo(level, projectName, actor)"));
        assertTrue(source.contains("this.undoRedo.redo(level, projectName, actor)"));
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

    @Test
    void shortcutStartNeverWaitsForTheIntegratedServerOnTheRenderThread() throws Exception {
        String source = Files.readString(
                Path.of("src/client/java/io/github/luma/client/input/UndoRedoKeyController.java")
        );

        assertTrue(source.contains("CompletableFuture.supplyAsync"));
        assertTrue(source.contains("Util.backgroundExecutor()"));
        assertFalse(source.contains(".join()"));
    }

    @Test
    void undoRedoStabilizesCausalFalloutBeforeSelectingTheAction() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/domain/service/UndoRedoService.java")
        );

        int stabilization = source.indexOf("this.capture.drainUndoRedoStabilization");
        int readiness = source.indexOf("this.capture.hasPendingUndoRedoStabilization");
        int selection = source.indexOf("direction.select(this.history");
        assertTrue(stabilization >= 0);
        assertTrue(readiness > stabilization);
        assertTrue(selection > readiness);
    }
}
