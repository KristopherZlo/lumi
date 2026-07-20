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
                "LumiDimensionsScreen.java",
                "LumiDimensionHistoryScreen.java",
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
        assertTrue(modal.contains("child instanceof Button button"));
        assertTrue(modal.contains("LegacyLumiTheme.PAGE_HEADER_HEIGHT"));
        assertTrue(modal.contains("LegacyLumiTheme.TITLEBAR"));
        assertTrue(modal.contains("page ? \"chevron-left\" : \"close\""));
        assertTrue(modal.contains("alignLegacyNavigation(x, y, width)"));
        assertTrue(modal.contains("frameY + FRAME_CONTROL_INSET"));
        assertTrue(modal.contains("legacy.legacyInitialized"));
        assertFalse(modal.contains("background.mouseClicked("));
        assertFalse(modal.contains("background.mouseReleased("));
        String dashboard = Files.readString(ui.resolve("LumiDashboardScreen.java"));
        assertTrue(dashboard.contains(
                "alignLegacyNavigation(x, y, layout.windowWidth())"));
        String onboarding = Files.readString(ui.resolve("LumiOnboardingScreen.java"));
        assertTrue(onboarding.contains(
                "alignLegacyNavigation(panelX, panelY, panelWidth)"));
        String page = Files.readString(ui.resolve("LumiLegacyPageScreen.java"));
        assertFalse(page.contains("forwardsParentInput"));
        assertTrue(page.contains("x < layout.contentX()"));
        assertTrue(page.contains("dashboard.mouseClicked(click, doubled)"));
        assertTrue(page.contains("page.dashboardParent()"));
    }

    @Test
    void textInputsUseCompactSingleLineHeight() throws Exception {
        assertEquals(14, LumiLegacyModalScreen.INPUT_HEIGHT);
        assertEquals(18, LumiLegacyModalScreen.INPUT_FRAME_HEIGHT);
        Path ui = Path.of("src/main/java/io/github/lumi/client/ui");
        for (String screen : List.of(
                "LumiDashboardScreen.java",
                "LumiSaveScreen.java",
                "LumiBranchScreen.java",
                "LumiZonesScreen.java",
                "LumiZoneDetailsScreen.java",
                "LumiPackageScreen.java",
                "LumiDimensionsScreen.java",
                "LumiDimensionHistoryScreen.java",
                "LumiDeleteZoneScreen.java")) {
            String source = Files.readString(ui.resolve(screen));
            assertFalse(source.matches("(?s).*new EditBox\\([^;]+, 20,\\s*Component.*"),
                    screen);
        }
    }
}
