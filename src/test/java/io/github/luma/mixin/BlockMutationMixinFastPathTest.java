package io.github.luma.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockMutationMixinFastPathTest {

    @Test
    void rejectsUntrackedMutationsBeforeReadingWorldState() throws Exception {
        this.assertEligibilityBeforeStateRead("LevelSetBlockMixin.java");
        this.assertEligibilityBeforeStateRead("LevelChunkSetBlockStateMixin.java");
    }

    @Test
    void nestedChunkMutationBypassesDuplicateInterception() throws Exception {
        String source = this.source("LevelChunkSetBlockStateMixin.java");
        this.assertBefore(source, "isWithinLevelSetBlockBoundary()", "LUMA_WORLD_OPERATIONS.blocksWorldMutations");
        assertTrue(source.contains("WorldMutationCaptureGuard.pushChunkSetBlockBoundary()"));
    }

    @Test
    void vanillaDirectSectionMutationBypassesCaptureServices() throws Exception {
        String source = this.source("LevelChunkSectionSetBlockStateMixin.java");
        this.assertBefore(source, "requiresInterception()", "blocksWorldMutation(section)");
    }

    private void assertEligibilityBeforeStateRead(String fileName) throws Exception {
        String source = this.source(fileName);
        this.assertBefore(source, "HistoryCaptureManager.shouldTrackPersistentMutation", "BlockState oldState");
    }

    private void assertBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue(firstIndex >= 0 && secondIndex > firstIndex);
    }

    private String source(String fileName) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/luma/mixin", fileName));
    }
}
