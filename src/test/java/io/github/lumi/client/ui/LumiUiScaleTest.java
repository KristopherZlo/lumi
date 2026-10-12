package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector2i;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LumiUiScaleTest {
    @AfterEach
    void clearProperty() {
        System.clearProperty(LumiUiScale.TARGET_GUI_SCALE_PROPERTY);
    }

    @Test
    void defaultScaleIsTwoWithoutReadingMinecraftOptions() {
        assertEquals(2, LumiUiScale.current().targetGuiScale());
    }

    @Test
    void fullscreenKeepsTheAvailableLogicalWidth() {
        LumiUiScale scale = LumiUiScale.current();

        assertEquals(640, scale.virtualSize(1280));
        assertEquals(960, scale.virtualSize(1920));
        assertEquals(1920, scale.virtualSize(3840));
    }

    @Test
    void virtualCoordinatesRemainInsideTheMinecraftViewport() {
        LumiUiScale scale = new LumiUiScale(3);

        int virtualWidth = scale.virtualSize(1920);

        assertEquals(640, virtualWidth);
        assertEquals(320.0, scale.virtualCoordinate(240, 4));
        assertTrue(virtualWidth * scale.renderScale(4) <= 480);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void renderingAndInputKeepTheSamePhysicalSizeAndPosition(int minecraftScale) {
        LumiUiScale scale = LumiUiScale.current();

        assertEquals(32.0, 16 * scale.renderScale(minecraftScale) * minecraftScale, 0.00001);
        assertEquals(320.0, scale.virtualCoordinate(640.0 / minecraftScale, minecraftScale),
                0.00001);
    }

    @Test
    void oddFramebufferDimensionsUseScaleTwoRoundingWithoutMinecraftRounding() {
        LumiUiScale scale = LumiUiScale.current();

        assertEquals(960, scale.virtualSize(1919));
        assertEquals(541, scale.virtualSize(1081));
        assertEquals(427, scale.virtualSize(853));
        assertEquals(1, scale.virtualSize(0));
    }

    @Test
    void developmentOverrideIsClamped() {
        System.setProperty(LumiUiScale.TARGET_GUI_SCALE_PROPERTY, "99");

        assertEquals(8, LumiUiScale.current().targetGuiScale());
    }

    @Test
    void invalidDevelopmentOverrideKeepsTheDefault() {
        System.setProperty(LumiUiScale.TARGET_GUI_SCALE_PROPERTY, "invalid");

        assertEquals(2, LumiUiScale.current().targetGuiScale());
    }

    @Test
    void tooltipPlacementUsesLumiBoundsInsteadOfMinecraftBounds() {
        LumiTooltipPositioner positioner = new LumiTooltipPositioner(
                (width, height, mouseX, mouseY, tooltipWidth, tooltipHeight) -> {
                    assertEquals(320, mouseX);
                    assertEquals(200, mouseY);
                    return new Vector2i(width - tooltipWidth, height - tooltipHeight);
                }, 960, 540);

        assertEquals(new Vector2i(900, 520), positioner.positionTooltip(480, 270, 320, 200, 60, 20));
    }
}
