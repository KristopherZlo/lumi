package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiOnboardingScreenTest {
    @Test
    void keepsTheLiveDashboardFreshBehindSpotlights() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiOnboardingScreen.java"));

        assertTrue(source.contains(
                "background instanceof LumiDashboardScreen dashboard"));
        int dashboardInit = source.indexOf("dashboard.init(width, height)");
        assertTrue(dashboardInit >= 0);
        assertTrue(dashboardInit
                < source.indexOf("dashboard.onboardingTarget("));
        assertTrue(source.contains("dashboard.tick()"));
        assertTrue(source.contains("actions.save().open("));
        assertTrue(source.contains("OnboardingEvent.OperationStarted"));
        assertTrue(source.contains("actions.worldStep().accept(controller)"));
        assertTrue(source.contains("controller.handle(event)"));
        assertFalse(source.contains("OnboardingEvent.Direction.SKIP"));
        assertTrue(source.contains("OnboardingEvent.Direction.BACK"));
        assertTrue(source.contains("OnboardingEvent.Direction.NEXT"));
        assertTrue(source.contains("LumiHotkeys.bindingMatches("));
        assertTrue(source.contains("\"key.lumi.quick_save\""));
        assertTrue(source.contains("event.key() == GLFW.GLFW_KEY_ESCAPE"));
        assertTrue(source.contains("minecraft.setScreen(returnScreen)"));
        assertTrue(source.contains(
                "return !controller.current().spotlight()"));
        assertTrue(source.contains(
                "screen.pointerHovered(mouseX, mouseY)"));
    }

    @Test
    void keepsPanelNavigationInsideSupportedViewports() {
        assertPanelGeometry(360, 224, 194);
        assertPanelGeometry(240, 216, 186);
        assertPanelGeometry(200, 176, 146);
        assertPanelGeometry(180, 156, 126);
    }

    private static void assertPanelGeometry(
            int screenHeight, int expectedHeight, int expectedActionOffset) {
        int panelHeight = LumiOnboardingScreen.fittedPanelHeight(screenHeight);
        assertEquals(expectedHeight, panelHeight);
        assertEquals(expectedActionOffset,
                LumiOnboardingScreen.panelActionOffset(panelHeight));
        assertTrue(expectedActionOffset + 20 <= panelHeight);
    }
}
