package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AutomaticVersionScopeTest {
    @Test
    void automaticVersionsIgnoreAmbientWorldTicks() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/minecraft/runtime/FabricDimensionRuntime.java"));
        int start = source.indexOf("private void scheduleAutoVersion()");
        int end = source.indexOf("public synchronized Optional<OperationJournal>", start);
        String scheduling = source.substring(start, end);

        assertTrue(scheduling.contains("mutations.hasPendingBuilderChanges()"));
        assertTrue(scheduling.contains(
                "mutations.builderSnapshot(workspace::includes)"));
        assertTrue(scheduling.contains(
                "scopedSavePreparation(dirty.generations()::containsKey)"));
        assertTrue(scheduling.contains("lastAutoVersion = fingerprint"));
    }
}
