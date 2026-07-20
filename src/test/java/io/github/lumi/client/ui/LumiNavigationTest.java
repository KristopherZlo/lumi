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
    void sidebarUsesV2OrderAndPagesOwnTheirShell() throws Exception {
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
        String neutral = Files.readString(ui.resolve("LumiScreen.java"));
        assertTrue(modal.contains("background.render("));
        assertTrue(neutral.contains("GLFW_HAND_CURSOR"));
        assertTrue(neutral.contains("child instanceof Button button"));
        assertTrue(neutral.contains("minecraft.screen == this"));
        assertTrue(neutral.contains("hovered == handCursorActive"));
        assertTrue(neutral.contains("LumiTheme.PAGE_HEADER_HEIGHT"));
        assertTrue(neutral.contains("LumiTheme.TITLEBAR"));
        assertTrue(neutral.contains("page ? \"chevron-left\" : \"close\""));
        assertTrue(neutral.contains("alignNavigation(x, y, width)"));
        assertTrue(neutral.contains("frameY + FRAME_CONTROL_INSET"));
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
        String session = Files.readString(ui.resolve("LumiPageSession.java"));
        assertTrue(page.contains("private final LumiPageSession pageSession"));
        assertTrue(session.contains("new EnumMap<>(ProjectTab.class)"));
        assertTrue(session.contains("destination.accept(historyPage)"));
        assertTrue(page.contains("extends LumiScreen"));
        assertTrue(page.contains("addSidebarButtons()"));
        assertTrue(page.contains("pageSession.open(destination)"));
        assertFalse(page.contains("dashboardParent"));
        assertFalse(page.contains("dashboard.mouseClicked"));
        assertFalse(page.contains("dashboard.pointerHovered"));
        assertEquals(LumiScreen.class, LumiPageScreen.class.getSuperclass());
        assertEquals(LumiScreen.class, LumiModalScreen.class.getSuperclass());
    }

    @Test
    void historyUsesTheSharedPageShell() {
        assertEquals(LumiPageScreen.class, LumiDashboardScreen.class.getSuperclass());
    }

    @Test
    void textInputsUseCompactSingleLineHeight() throws Exception {
        assertEquals(18, LumiTextField.FRAME_HEIGHT);
        assertEquals(6, LumiTextField.HORIZONTAL_PADDING);
        assertEquals(4, LumiTextField.VERTICAL_PADDING);
        String neutral = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiScreen.java"));
        assertTrue(neutral.contains("new LumiTextField(font, x, y, width, label)"));
        String field = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiTextField.java"));
        assertTrue(field.contains("y + VERTICAL_PADDING"));
        assertTrue(field.contains("mouseY < frameY + FRAME_HEIGHT"));
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
        assertEquals(200, LumiPageLayout.doubledSearchWidth(100, 400));
        assertEquals(86, LumiPageLayout.doubledSearchWidth(100, 86));
    }
}
