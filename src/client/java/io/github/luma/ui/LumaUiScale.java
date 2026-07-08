package io.github.luma.ui;

public final class LumaUiScale {

    public static final String TARGET_GUI_SCALE_PROPERTY = "lumi.ui.targetGuiScale";
    public static final String ICON_BUTTON_WIDTH_PROPERTY = "lumi.ui.iconButtonWidth";
    public static final String ICON_BUTTON_HEIGHT_PROPERTY = "lumi.ui.iconButtonHeight";
    public static final String ICON_DRAW_SIZE_PROPERTY = "lumi.ui.iconDrawSize";

    private static final int DEFAULT_TARGET_GUI_SCALE = 2;
    private static final int DEFAULT_ICON_BUTTON_WIDTH = 26;
    private static final int DEFAULT_ICON_BUTTON_HEIGHT = 18;
    private static final int DEFAULT_ICON_DRAW_SIZE = 12;

    private LumaUiScale() {
    }

    public static float renderScale(int currentGuiScale) {
        return targetGuiScale() / (float) Math.max(1, currentGuiScale);
    }

    public static int virtualSize(int scaledSize, int currentGuiScale) {
        return Math.max(1, (int) (((long) scaledSize * Math.max(1, currentGuiScale)) / targetGuiScale()));
    }

    public static double virtualCoordinate(double coordinate, int currentGuiScale) {
        return coordinate / renderScale(currentGuiScale);
    }

    public static float targetPixelOffset() {
        return 1.0F / targetGuiScale();
    }

    public static int iconButtonWidth() {
        return intProperty(ICON_BUTTON_WIDTH_PROPERTY, DEFAULT_ICON_BUTTON_WIDTH, 10, 64);
    }

    public static int iconButtonHeight() {
        return intProperty(ICON_BUTTON_HEIGHT_PROPERTY, DEFAULT_ICON_BUTTON_HEIGHT, 10, 64);
    }

    public static int iconDrawSize() {
        return intProperty(ICON_DRAW_SIZE_PROPERTY, DEFAULT_ICON_DRAW_SIZE, 8, 24);
    }

    private static int targetGuiScale() {
        return intProperty(TARGET_GUI_SCALE_PROPERTY, DEFAULT_TARGET_GUI_SCALE, 1, 8);
    }

    private static int intProperty(String property, int fallback, int min, int max) {
        return clamp(Integer.getInteger(property, fallback), min, max);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
