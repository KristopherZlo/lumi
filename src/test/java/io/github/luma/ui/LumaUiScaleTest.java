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
    void virtualSizeMatchesGuiScaleTwoAcrossMinecraftGuiScales() {
        Assertions.assertEquals(960, LumaUiScale.virtualSize(1920, 1));
        Assertions.assertEquals(960, LumaUiScale.virtualSize(960, 2));
        Assertions.assertEquals(960, LumaUiScale.virtualSize(640, 3));
        Assertions.assertEquals(540, LumaUiScale.virtualSize(360, 3));
    }

    @Test
    void defaultIconButtonsStayNearNativePixelSizeAtTargetScale() {
        Assertions.assertEquals(26, LumaUiScale.iconButtonWidth());
        Assertions.assertEquals(18, LumaUiScale.iconButtonHeight());
        Assertions.assertEquals(12, LumaUiScale.iconDrawSize());
    }

    @Test
    void devPropertiesCanTuneIconButtonSizes() {
        System.setProperty(LumaUiScale.ICON_BUTTON_WIDTH_PROPERTY, "28");
        System.setProperty(LumaUiScale.ICON_BUTTON_HEIGHT_PROPERTY, "18");
        System.setProperty(LumaUiScale.ICON_DRAW_SIZE_PROPERTY, "13");

        Assertions.assertEquals(28, LumaUiScale.iconButtonWidth());
        Assertions.assertEquals(18, LumaUiScale.iconButtonHeight());
        Assertions.assertEquals(13, LumaUiScale.iconDrawSize());
    }

    @Test
    void devPropertiesAreClamped() {
        System.setProperty(LumaUiScale.TARGET_GUI_SCALE_PROPERTY, "0");
        System.setProperty(LumaUiScale.ICON_BUTTON_WIDTH_PROPERTY, "200");
        System.setProperty(LumaUiScale.ICON_BUTTON_HEIGHT_PROPERTY, "1");
        System.setProperty(LumaUiScale.ICON_DRAW_SIZE_PROPERTY, "99");

        Assertions.assertEquals(1920, LumaUiScale.virtualSize(1920, 1));
        Assertions.assertEquals(64, LumaUiScale.iconButtonWidth());
        Assertions.assertEquals(10, LumaUiScale.iconButtonHeight());
        Assertions.assertEquals(24, LumaUiScale.iconDrawSize());
    }
}
