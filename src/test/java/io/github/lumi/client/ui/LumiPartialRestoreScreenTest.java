package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiPartialRestoreScreenTest {
    @Test
    void usesOnlySwordBoundsAndKeepsExactPreviewBeforeApply() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiPartialRestoreScreen.java"));
        String fullRestore = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiRestoreScreen.java"));

        assertTrue(source.contains("BlockBox selection"));
        assertTrue(source.contains("form.beginPreview"));
        assertTrue(source.contains("form.previewToken()"));
        assertTrue(source.contains("mode_outside_selection"));
        assertFalse(source.contains("EditBox"));
        assertFalse(source.contains("setMinX"));
        assertFalse(fullRestore.contains("BlockBox"));
        assertFalse(fullRestore.contains("PartialRestorePlanPayload"));
        assertFalse(fullRestore.contains("EditBox"));
        assertTrue(fullRestore.contains("luma.action.restore"));
        assertFalse(fullRestore.contains("restore_without_entities"));
        assertFalse(fullRestore.contains("restore_whole_save"));
        assertFalse(fullRestore.contains("includeEntities"));
    }
}
