package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LumiLegacyNavigationTest {
    @Test
    void sidebarPagesRetainTheProjectShellAndModalsRetainTheirParent() throws Exception {
        Path ui = Path.of("src/main/java/io/github/lumi/client/ui");
        for (String page : List.of(
                "LumiZonesScreen.java",
                "LumiBranchesScreen.java",
                "LumiWorkspacesScreen.java",
                "LumiPackageScreen.java",
                "LumiSettingsScreen.java",
                "LumiDeletedVersionsScreen.java",
                "LumiMoreScreen.java")) {
            String source = Files.readString(ui.resolve(page));
            assertTrue(source.contains("extends LumiLegacyPageScreen"), page);
            assertTrue(source.contains("renderLegacyPage("), page);
        }

        String modal = Files.readString(ui.resolve("LumiLegacyModalScreen.java"));
        assertTrue(modal.contains("background.render("));
        assertTrue(modal.contains("forwardsParentInput()"));
    }
}
