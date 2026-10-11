package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiMergeScreenTest {
    @Test
    void confirmationRequiresTheExactDisplayedSourceName() {
        assertFalse(LumiMergeScreen.confirmationMatches("workspace/123/Castle", ""));
        assertFalse(LumiMergeScreen.confirmationMatches("workspace/123/Castle", "castle"));
        assertFalse(LumiMergeScreen.confirmationMatches("workspace/123/Castle", "Castle "));
        assertFalse(LumiMergeScreen.confirmationMatches("workspace/123/Castle", "main"));
        assertTrue(LumiMergeScreen.confirmationMatches("workspace/123/Castle", "Castle"));
        assertTrue(LumiMergeScreen.confirmationMatches("Башня", "Башня"));
    }
    @Test
    void requiresAnIsometricSourceTargetPreviewBeforeMerge() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiMergeScreen.java"));

        assertTrue(source.contains("pendingSource = source"));
        assertTrue(source.contains("previews.texture(dimensionId, branch.head())"));
        assertTrue(source.contains("luma.ideas.merge_source_preview"));
        assertTrue(source.contains("luma.ideas.merge_target_preview"));
        assertTrue(source.indexOf("pendingSource = source")
                < source.indexOf("merge.accept(source)"));
    }
}
