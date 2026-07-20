package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LumiNavigationTest {
    @Test
    void sidebarUsesV2OrderAndPagesBlockBackgroundInput() throws Exception {
        assertEquals(List.of(
                ProjectTab.HISTORY,
                ProjectTab.ZONES,
                ProjectTab.VARIANTS,
                ProjectTab.COMPARE,
                ProjectTab.IMPORT_EXPORT,
                ProjectTab.SETTINGS,
                ProjectTab.MORE), List.of(ProjectTab.values()));

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
            assertTrue(source.contains("extends LumiPageScreen"), page);
            assertTrue(source.contains("renderPage("), page);
        }

        String modal = Files.readString(ui.resolve("LumiModalScreen.java"));
        assertTrue(modal.contains("background.render("));
        assertTrue(modal.contains("GLFW_HAND_CURSOR"));
        assertTrue(modal.contains("child instanceof Button button"));
        assertTrue(modal.contains("minecraft.screen == this"));
        assertTrue(modal.contains("hovered == handCursorActive"));
        assertTrue(modal.contains("LumiTheme.PAGE_HEADER_HEIGHT"));
        assertTrue(modal.contains("LumiTheme.TITLEBAR"));
        assertTrue(modal.contains("page ? \"chevron-left\" : \"close\""));
        assertTrue(modal.contains("alignNavigation(x, y, width)"));
        assertTrue(modal.contains("frameY + FRAME_CONTROL_INSET"));
        assertTrue(modal.contains("screen.screenInitialized"));
        assertFalse(modal.contains("background.mouseClicked("));
        assertFalse(modal.contains("background.mouseReleased("));
        String dashboard = Files.readString(ui.resolve("LumiDashboardScreen.java"));
        assertTrue(dashboard.contains(
                "alignNavigation(x, y, layout.windowWidth())"));
        String onboarding = Files.readString(ui.resolve("LumiOnboardingScreen.java"));
        assertTrue(onboarding.contains(
                "alignNavigation(panelX, panelY, panelWidth)"));
        String page = Files.readString(ui.resolve("LumiPageScreen.java"));
        assertFalse(page.contains("forwardsParentInput"));
        assertTrue(page.contains("x < layout.contentX()"));
        assertTrue(page.contains("dashboard.mouseClicked(click, doubled)"));
        assertTrue(page.contains("page.dashboardParent()"));
        assertTrue(page.contains("dashboard.pointerHovered(mouseX, mouseY)"));
    }

    @Test
    void textInputsUseCompactSingleLineHeight() throws Exception {
        assertEquals(14, LumiModalScreen.INPUT_HEIGHT);
        assertEquals(18, LumiModalScreen.INPUT_FRAME_HEIGHT);
        String modal = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiModalScreen.java"));
        assertTrue(modal.contains("x + 6, y, Math.max(0, width - 12)"));
        assertTrue(modal.contains("field.getX() - 6, field.getY()"));
        assertTrue(modal.contains("INPUT_FRAME_HEIGHT, label"));
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
