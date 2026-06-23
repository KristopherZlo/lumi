package io.github.luma.ui.navigation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectSidebarNavigationTest {

    @Test
    void sidebarIncludesHistoryGraphTab() throws IOException {
        String source = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/navigation/ProjectSidebarNavigation.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(
                source.contains("luma.more.tab_history_graph") && source.contains("openHistoryGraph"),
                "Project sidebar should expose History graph directly"
        );
    }
}
