package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void selectionActionsFitTheMinimumViewport() {
        for (int[] viewport : new int[][] {{640, 360}, {427, 240}, {320, 180}}) {
            LegacyModalLayout layout = LumiPartialRestoreScreen.fitPanel(
                    viewport[0], viewport[1]);
            int height = layout.height();
            assertTrue(layout.x() + layout.width() <= viewport[0]);
            assertTrue(layout.y() + height <= viewport[1]);
            assertTrue(LumiPartialRestoreScreen.modeOffset(height) + 20
                    <= LumiPartialRestoreScreen.previewOffset(height));
            assertTrue(LumiPartialRestoreScreen.previewOffset(height) + 20
                    <= LumiPartialRestoreScreen.statusOffset(height));
            assertTrue(LumiPartialRestoreScreen.statusOffset(height) + 9
                    <= LumiPartialRestoreScreen.cancelOffset(height));
            assertTrue(LumiPartialRestoreScreen.cancelOffset(height) + 20 <= height);
        }
        assertEquals(164, LumiPartialRestoreScreen.fitPanel(320, 180).height());
    }
}
