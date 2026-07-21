package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BranchSwitchScopeTest {
    @Test
    void checkpointsAmbientWorkBeforeSwitchingBranches() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/minecraft/runtime/FabricDimensionRuntime.java"));

        assertTrue(source.contains("hidden/branch-switch/"));
        assertTrue(source.contains(
                "plan.source(), saved.commitId(), plan.target().commit()"));
        assertTrue(source.contains("new BranchSwitchRestorePublication("));
        assertTrue(source.contains(
                "saved.commitId(), saved.capturedGenerations()"));
    }
}
