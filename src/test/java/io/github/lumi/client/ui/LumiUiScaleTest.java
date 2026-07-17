package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LumiUiScaleTest {
    @AfterEach
    void clearProperty() {
        System.clearProperty(LumiUiScale.TARGET_GUI_SCALE_PROPERTY);
    }

    @Test
    void commonFramebuffersResolveToOneVirtualViewport() {
        assertProfile(1280, 720, 2);
        assertProfile(1920, 1080, 3);
        assertProfile(2560, 1440, 4);
        assertProfile(3840, 2160, 6);
    }

    @Test
    void virtualCoordinatesRemainInsideTheMinecraftViewport() {
        LumiUiScale scale = new LumiUiScale(3);

        int virtualWidth = scale.virtualSize(480, 4);

        assertEquals(640, virtualWidth);
        assertEquals(320.0, scale.virtualCoordinate(240, 4));
        assertTrue(virtualWidth * scale.renderScale(4) <= 480);
    }

    @Test
    void developmentOverrideIsClamped() {
        System.setProperty(LumiUiScale.TARGET_GUI_SCALE_PROPERTY, "99");

        assertEquals(8, LumiUiScale.forFramebuffer(1920, 1080).targetGuiScale());
    }

    private static void assertProfile(int width, int height, int expectedScale) {
        LumiUiScale scale = LumiUiScale.forFramebuffer(width, height);
        assertEquals(expectedScale, scale.targetGuiScale());
        assertEquals(640, width / scale.targetGuiScale());
        assertEquals(360, height / scale.targetGuiScale());
    }
}
