package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiMergeScreenTest {
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
