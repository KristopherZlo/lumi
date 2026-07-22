package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PendingBuilderBoundaryTest {
    @Test
    void pendingStatisticsUseTheBuilderSubset() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/minecraft/runtime/FabricDimensionRuntime.java"));

        assertTrue(source.contains(
                "return mutations.builderSnapshot(workspace::includes);"));
        assertTrue(source.contains(
                "mutations.durabilityBoundary(boundary)"));
        assertTrue(source.contains(
                "mutations.isDurable(durability)"));
    }
}
