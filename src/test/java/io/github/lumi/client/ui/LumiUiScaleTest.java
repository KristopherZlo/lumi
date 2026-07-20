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
    void liveViewportUsesMinecraftGuiScale() {
        assertEquals(2, LumiUiScale.forGuiScale(2).targetGuiScale());
        assertEquals(4, LumiUiScale.forGuiScale(4).targetGuiScale());
    }

    @Test
    void fullscreenKeepsTheAvailableLogicalWidth() {
        LumiUiScale scale = LumiUiScale.forGuiScale(2);

        assertEquals(960, scale.virtualSize(960, 2));
        assertEquals(1920, scale.virtualSize(1920, 2));
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

        assertEquals(8, LumiUiScale.forGuiScale(2).targetGuiScale());
    }
}
