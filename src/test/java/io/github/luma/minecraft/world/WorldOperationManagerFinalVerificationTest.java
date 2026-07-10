package io.github.luma.minecraft.world;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldOperationManagerFinalVerificationTest {

    @Test
    void preparedApplyCompletionWaitsForFinalVerificationGate() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/luma/minecraft/world/WorldOperationManager.java"
        ));

        int dispatcherDone = source.indexOf("this.dispatcher == null || !this.dispatcher.hasPending()");
        int finalGate = source.indexOf("this.finalVerificationGate.advance(");
        int completion = source.indexOf("return this.advanceCompletion();", finalGate);

        assertTrue(finalGate > dispatcherDone, "Final verification must run after all batches and replay drains");
        assertTrue(completion > finalGate, "Completion must wait for final verification");
    }

    @Test
    void preparedApplyCompletionWaitsForEntireExactReplayQueue() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/luma/minecraft/world/WorldOperationManager.java"
        ));

        assertTrue(source.contains("return !this.exactReplayStateQueue.hasPending();"));
        assertFalse(source.contains("!this.exactReplayStateQueue.hasPending() || reapplied > 0"));
    }
}
