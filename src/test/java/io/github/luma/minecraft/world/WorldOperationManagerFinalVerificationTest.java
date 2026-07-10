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

    @Test
    void finalVerificationKeepsTheOriginalTargetsRemovedByNoOpPruning() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/luma/minecraft/world/WorldOperationManager.java"
        ));

        assertTrue(source.contains("this.currentBatch = this.pruneNoOpBatch(this.currentTargetBatch);"));
        assertTrue(source.contains("this.finalVerificationGate.record(this.currentTargetBatch);"));
    }

    @Test
    void verificationUsesTheTickDeadlineInBothPasses() throws Exception {
        String manager = Files.readString(Path.of(
                "src/main/java/io/github/luma/minecraft/world/WorldOperationManager.java"
        ));
        String finalGate = Files.readString(Path.of(
                "src/main/java/io/github/luma/minecraft/world/WorldApplyFinalVerificationGate.java"
        ));

        assertTrue(manager.contains("verificationService.advance(this.level(), batch, deadlineNanos)"));
        assertTrue(finalGate.contains("verificationService.advance(level, batch, deadlineNanos)"));
    }
}
