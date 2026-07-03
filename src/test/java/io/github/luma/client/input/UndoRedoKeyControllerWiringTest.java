package io.github.luma.client.input;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoRedoKeyControllerWiringTest {

    @Test
    void localPlayerNameScopesLiveUndoRedoSelection() throws Exception {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/client/input/UndoRedoKeyController.java"));

        assertTrue(source.contains("String actor = this.playerActor(client);"));
        assertTrue(source.contains("this.undoRedoService.undo(level, project.name(), actor)"));
        assertTrue(source.contains("this.undoRedoService.redo(level, project.name(), actor)"));
    }

    @Test
    void nativeExternalUndoRedoUsesProjectWideSelectionBeforeActorFiltering() throws Exception {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/client/input/UndoRedoKeyController.java"));

        String nativePath = source.substring(source.indexOf("private boolean tryNativeExternalUndoRedo"));
        assertTrue(nativePath.contains("this.historyManager.selectUndo(project.id().toString())"));
        assertTrue(nativePath.contains("this.historyManager.selectRedo(project.id().toString())"));
        assertTrue(nativePath.contains("this.externalUndoRedoPolicy.decisionForAction("));
    }

    @Test
    void nativeExternalUndoRedoDoesNotSelectOnlyLocalPlayerStack() throws Exception {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/client/input/UndoRedoKeyController.java"));
        String nativePath = source.substring(source.indexOf("private boolean tryNativeExternalUndoRedo"));

        assertTrue(!nativePath.contains("this.historyManager.selectUndo(project.id().toString(), actor)"));
        assertTrue(!nativePath.contains("this.historyManager.selectRedo(project.id().toString(), actor)"));
    }

    @Test
    void nativeExternalUndoRedoVerifiesWorldBeforeCompletingHistory() throws Exception {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/client/input/UndoRedoKeyController.java"));
        String nativePath = source.substring(source.indexOf("private boolean tryNativeExternalUndoRedo"));

        assertTrue(nativePath.contains("this.awaitQueuedServerWork(level.getServer())"));
        assertTrue(nativePath.contains("this.nativeUndoRedoVerifier.matches(level, action, undo)"));
        assertTrue(
                nativePath.indexOf("this.nativeUndoRedoVerifier.matches(level, action, undo)")
                        < nativePath.indexOf("this.historyManager.completeUndo(project.id().toString(), selection)")
        );
    }

    @Test
    void undoRedoServiceStillScopesLumiReplayToLocalPlayer() throws Exception {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/client/input/UndoRedoKeyController.java"));

        assertTrue(source.contains("String actor = this.playerActor(client);"));
        assertTrue(source.contains("this.undoRedoService.undo(level, project.name(), actor)"));
        assertTrue(source.contains("this.undoRedoService.redo(level, project.name(), actor)"));
    }
}
