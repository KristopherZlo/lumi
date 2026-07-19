package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LumiLegacyNavigationTest {
    @Test
    void sidebarUsesLegacyOrderAndPagesBlockBackgroundInput() throws Exception {
        assertEquals(List.of(
                LegacyProjectTab.HISTORY,
                LegacyProjectTab.ZONES,
                LegacyProjectTab.VARIANTS,
                LegacyProjectTab.COMPARE,
                LegacyProjectTab.IMPORT_EXPORT,
                LegacyProjectTab.SETTINGS,
                LegacyProjectTab.MORE), List.of(LegacyProjectTab.values()));

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
        assertTrue(modal.contains("GLFW_HAND_CURSOR"));
        assertTrue(modal.contains("page ? \"chevron-left\" : \"close\""));
        assertFalse(modal.contains("background.mouseClicked("));
        assertFalse(modal.contains("background.mouseReleased("));
        String page = Files.readString(ui.resolve("LumiLegacyPageScreen.java"));
        assertFalse(page.contains("forwardsParentInput"));
        assertTrue(page.contains("x < layout.contentX()"));
        assertTrue(page.contains("dashboard.mouseClicked(click, doubled)"));
    }

    @Test
    void textInputsUseCompactSingleLineHeight() throws Exception {
        Path ui = Path.of("src/main/java/io/github/lumi/client/ui");
        for (String screen : List.of(
                "LumiDashboardScreen.java",
                "LumiSaveScreen.java",
                "LumiBranchScreen.java",
                "LumiZonesScreen.java",
                "LumiZoneDetailsScreen.java",
                "LumiPackageScreen.java",
                "LumiWorkspacesScreen.java",
                "LumiDeleteZoneScreen.java")) {
            String source = Files.readString(ui.resolve(screen));
            assertFalse(source.matches("(?s).*new EditBox\\([^;]+, 20,\\s*Component.*"),
                    screen);
        }
    }
}
