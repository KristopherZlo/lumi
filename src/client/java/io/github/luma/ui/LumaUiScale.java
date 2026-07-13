package io.github.luma.ui;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

public record LumaUiScale(int targetGuiScale) {

    public static final String TARGET_GUI_SCALE_PROPERTY = "lumi.ui.targetGuiScale";
    public static final String ICON_BUTTON_WIDTH_PROPERTY = "lumi.ui.iconButtonWidth";
    public static final String ICON_BUTTON_HEIGHT_PROPERTY = "lumi.ui.iconButtonHeight";
    public static final String ICON_DRAW_SIZE_PROPERTY = "lumi.ui.iconDrawSize";

    private static final int REFERENCE_WIDTH = 640;
    private static final int REFERENCE_HEIGHT = 360;
    private static final int MIN_TARGET_GUI_SCALE = 2;
    private static final int MAX_TARGET_GUI_SCALE = 8;
    private static final int DEFAULT_ICON_BUTTON_WIDTH = 26;
    private static final int DEFAULT_ICON_BUTTON_HEIGHT = 18;
    private static final int DEFAULT_ICON_DRAW_SIZE = 12;
    private static final int NATIVE_ICON_DRAW_SIZE_AT_SCALE_THREE = 8;

    public LumaUiScale {
        targetGuiScale = clamp(targetGuiScale, 1, MAX_TARGET_GUI_SCALE);
    }

    public static LumaUiScale current() {
        Minecraft client = Minecraft.getInstance();
        Window window = client == null ? null : client.getWindow();
        return window == null
                ? forFramebuffer(REFERENCE_WIDTH * MIN_TARGET_GUI_SCALE, REFERENCE_HEIGHT * MIN_TARGET_GUI_SCALE)
                : forFramebuffer(window.getWidth(), window.getHeight());
    }

    public static LumaUiScale forFramebuffer(int width, int height) {
        int automaticTarget = automaticTargetGuiScale(width, height);
        return new LumaUiScale(intProperty(
                TARGET_GUI_SCALE_PROPERTY,
                automaticTarget,
                1,
                MAX_TARGET_GUI_SCALE
        ));
    }

    public float renderScale(int currentGuiScale) {
        return this.targetGuiScale / (float) Math.max(1, currentGuiScale);
    }

    public int virtualSize(int scaledSize, int currentGuiScale) {
        return Math.max(1, (int) (((long) scaledSize * Math.max(1, currentGuiScale)) / this.targetGuiScale));
    }

    public double virtualCoordinate(double coordinate, int currentGuiScale) {
        return coordinate / this.renderScale(currentGuiScale);
    }

    public float targetPixelOffset() {
        return 1.0F / this.targetGuiScale;
    }

    public static int iconButtonWidth() {
        return intProperty(ICON_BUTTON_WIDTH_PROPERTY, DEFAULT_ICON_BUTTON_WIDTH, 10, 64);
    }

    public static int iconButtonHeight() {
        return intProperty(ICON_BUTTON_HEIGHT_PROPERTY, DEFAULT_ICON_BUTTON_HEIGHT, 10, 64);
    }

    public int iconDrawSize() {
        int automaticSize = this.targetGuiScale == 3
                ? NATIVE_ICON_DRAW_SIZE_AT_SCALE_THREE
                : DEFAULT_ICON_DRAW_SIZE;
        return intProperty(ICON_DRAW_SIZE_PROPERTY, automaticSize, 8, 24);
    }

    private static int automaticTargetGuiScale(int width, int height) {
        int fittingScale = Math.min(
                Math.max(1, width) / REFERENCE_WIDTH,
                Math.max(1, height) / REFERENCE_HEIGHT
        );
        if (fittingScale >= 8) {
            return 8;
        }
        if (fittingScale >= 6) {
            return 6;
        }
        return clamp(fittingScale, MIN_TARGET_GUI_SCALE, 4);
    }

    private static int intProperty(String property, int fallback, int min, int max) {
        return clamp(Integer.getInteger(property, fallback), min, max);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
