package io.github.lumi.client.ui;

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
    }
}
