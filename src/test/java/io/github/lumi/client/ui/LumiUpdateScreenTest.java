package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiUpdateScreenTest {
    @Test
    void exposesAllLegacyReleaseActions() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiUpdateScreen.java"));

        assertTrue(source.contains("luma.action.download_update"));
        assertTrue(source.contains("luma.action.open_changelog"));
        assertTrue(source.contains("luma.action.later"));
        assertTrue(source.contains("luma.action.dont_show_version"));
        assertTrue(source.contains("changelogUri()"));
        assertTrue(source.contains("preferences.ignored(release.version())"));
        assertTrue(source.contains("preferences.dismiss("));
        assertTrue(source.contains("startCheck();"));
        assertTrue(source.contains("luma.action.checking_updates"));
        assertFalse(source.contains("this::check"));
    }

    @Test
    void releaseActionsFitTheMinimumViewport() {
        for (int[] viewport : new int[][] {{640, 360}, {427, 240}, {320, 180}}) {
            LegacyModalLayout layout = LumiUpdateScreen.fitPanel(
                    viewport[0], viewport[1]);
            int height = layout.height();
            assertTrue(layout.x() + layout.width() <= viewport[0]);
            assertTrue(layout.y() + height <= viewport[1]);
            assertTrue(LumiUpdateScreen.updateResultBottomOffset(height)
                    < LumiUpdateScreen.firstActionOffset(height));
            assertTrue(LumiUpdateScreen.firstActionOffset(height) + 20
                    <= LumiUpdateScreen.bottomActionOffset(height));
            assertTrue(LumiUpdateScreen.bottomActionOffset(height) + 20 <= height);
        }
        int height = LumiUpdateScreen.fitPanel(320, 180).height();
        assertEquals(156, height);
        assertFalse(LumiUpdateScreen.fitsResultLine(98,
                LumiUpdateScreen.updateResultBottomOffset(height)));
    }
}
