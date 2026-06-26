package io.github.luma.ui.navigation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectSidebarNavigationTest {

    @Test
    void sidebarKeepsSupportVisibleWithoutSeparateHistoryGraphTab() throws IOException {
        String source = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/navigation/ProjectSidebarNavigation.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(
                source.contains("openHistoryGraph"),
                "Project sidebar should not expose History graph as a separate tab"
        );
        assertTrue(
                source.contains("supportFooter()"),
                "Project sidebar should keep support visible in the fixed footer"
        );
    }

    @Test
    void supportFooterShowsCreditAndPackagedVersion() throws IOException {
        String source = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/navigation/ProjectSidebarNavigation.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("luma.window.credit"));
        assertTrue(source.contains("luma.window.mod_version"));
        assertTrue(source.contains("getModContainer(LumaMod.MOD_ID)"));
    }
}
