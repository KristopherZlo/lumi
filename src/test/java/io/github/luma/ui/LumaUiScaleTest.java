package io.github.luma.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LumaUiScaleTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty(LumaUiScale.TARGET_GUI_SCALE_PROPERTY);
        System.clearProperty(LumaUiScale.ICON_BUTTON_WIDTH_PROPERTY);
        System.clearProperty(LumaUiScale.ICON_BUTTON_HEIGHT_PROPERTY);
        System.clearProperty(LumaUiScale.ICON_DRAW_SIZE_PROPERTY);
    }

    @Test
    void commonDisplaySizesResolveToAConsistentVirtualViewport() {
        this.assertProfile(1280, 720, 2);
        this.assertProfile(1920, 1080, 3);
        this.assertProfile(2560, 1440, 4);
        this.assertProfile(3840, 2160, 6);
    }

    @Test
    void unsupportedIntermediateProfilesRoundDown() {
        Assertions.assertEquals(4, LumaUiScale.forFramebuffer(3200, 1800).targetGuiScale());
        Assertions.assertEquals(6, LumaUiScale.forFramebuffer(4480, 2520).targetGuiScale());
    }

    @Test
    void scaleThreeKeepsTwentyFourPixelIconsAtNativeFramebufferSize() {
        LumaUiScale scale = LumaUiScale.forFramebuffer(1920, 1080);

        Assertions.assertEquals(3, scale.targetGuiScale());
        Assertions.assertEquals(8, scale.iconDrawSize());
        Assertions.assertEquals(24, scale.iconDrawSize() * scale.targetGuiScale());
    }

    @Test
    void virtualSizeMatchesResolvedScaleAcrossMinecraftGuiScales() {
        LumaUiScale scale = new LumaUiScale(2);

        Assertions.assertEquals(960, scale.virtualSize(1920, 1));
        Assertions.assertEquals(960, scale.virtualSize(960, 2));
        Assertions.assertEquals(960, scale.virtualSize(640, 3));
        Assertions.assertEquals(540, scale.virtualSize(360, 3));
    }

    @Test
    void virtualSizeDoesNotRoundPastMinecraftViewport() {
        LumaUiScale scale = new LumaUiScale(2);
        int virtualSize = scale.virtualSize(641, 3);

        Assertions.assertEquals(961, virtualSize);
        Assertions.assertTrue(virtualSize * scale.renderScale(3) <= 641.0F);
    }

    @Test
    void defaultIconButtonsStayNearNativePixelSizeAtTargetScale() {
        Assertions.assertEquals(26, LumaUiScale.iconButtonWidth());
        Assertions.assertEquals(18, LumaUiScale.iconButtonHeight());
        Assertions.assertEquals(12, new LumaUiScale(2).iconDrawSize());
    }

    @Test
    void devPropertiesCanTuneIconButtonSizes() {
        System.setProperty(LumaUiScale.ICON_BUTTON_WIDTH_PROPERTY, "28");
        System.setProperty(LumaUiScale.ICON_BUTTON_HEIGHT_PROPERTY, "18");
        System.setProperty(LumaUiScale.ICON_DRAW_SIZE_PROPERTY, "13");

        Assertions.assertEquals(28, LumaUiScale.iconButtonWidth());
        Assertions.assertEquals(18, LumaUiScale.iconButtonHeight());
        Assertions.assertEquals(13, new LumaUiScale(3).iconDrawSize());
    }

    @Test
    void devPropertiesAreClamped() {
        System.setProperty(LumaUiScale.TARGET_GUI_SCALE_PROPERTY, "0");
        System.setProperty(LumaUiScale.ICON_BUTTON_WIDTH_PROPERTY, "200");
        System.setProperty(LumaUiScale.ICON_BUTTON_HEIGHT_PROPERTY, "1");
        System.setProperty(LumaUiScale.ICON_DRAW_SIZE_PROPERTY, "99");

        LumaUiScale scale = LumaUiScale.forFramebuffer(1920, 1080);

        Assertions.assertEquals(1, scale.targetGuiScale());
        Assertions.assertEquals(1920, scale.virtualSize(1920, 1));
        Assertions.assertEquals(64, LumaUiScale.iconButtonWidth());
        Assertions.assertEquals(10, LumaUiScale.iconButtonHeight());
        Assertions.assertEquals(24, scale.iconDrawSize());
    }

    private void assertProfile(int width, int height, int expectedScale) {
        LumaUiScale scale = LumaUiScale.forFramebuffer(width, height);

        Assertions.assertEquals(expectedScale, scale.targetGuiScale());
        Assertions.assertEquals(640, width / scale.targetGuiScale());
        Assertions.assertEquals(360, height / scale.targetGuiScale());
    }
}
