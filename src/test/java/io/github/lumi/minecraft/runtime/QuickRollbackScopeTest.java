package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class QuickRollbackScopeTest {
    @Test
    void restoresTheWholePendingWorkspaceIntoOneCheckpointAction() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/minecraft/runtime/FabricDimensionRuntime.java"));

        assertTrue(source.contains(
                "createQuickRollback(author, pending, expected)"));
        assertTrue(source.contains("mutations.snapshot().generations()"));
        assertTrue(!source.contains("clearableQuickRollbackKeys("));
        assertTrue(source.contains("liveActions.pushCheckpoint("));
        assertTrue(source.contains("sessionCheckpoints.name(operationId)"));
        assertTrue(!source.contains("\"hidden/rollback/\" + operationId"));
        assertTrue(!source.contains("liveActions.recordRestore("));
        assertTrue(!source.contains("new LiveRecordedMutation("));
        assertTrue(!source.contains("liveWorld.prepareRestore("));
        assertTrue(!source.contains("liveEntityWorld.prepareRestore("));
        assertTrue(source.contains(
                "mutations, saved.capturedGenerations())"));
    }
}
