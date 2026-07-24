package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class QuickRollbackScopeTest {
    @Test
    void restoresTheWholePendingWorkspaceIntoOneLiveAction() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/minecraft/runtime/FabricDimensionRuntime.java"));

        assertTrue(source.contains(
                "createQuickRollback(author, pending, action)"));
        assertTrue(source.contains("mutations.snapshot().generations()"));
        assertTrue(!source.contains("clearableQuickRollbackKeys("));
        assertTrue(!source.contains(
                "createQuickRollback(author, builder, selection, action)"));
        int blocks = source.indexOf("liveWorld.prepareRestore(");
        int entities = source.indexOf("liveEntityWorld.prepareRestore(");
        int record = source.indexOf("liveActions.recordRestore(");
        assertTrue(blocks >= 0 && entities > blocks && record > entities);
        assertTrue(source.contains(
                "mutations, saved.capturedGenerations())"));
    }
}
