package io.github.lumi.client.ui;

/** Fixed Lumi pixel scale with matching render and input coordinates. */
public record LumiUiScale(int targetGuiScale) {
    public static final String TARGET_GUI_SCALE_PROPERTY = "lumi.ui.targetGuiScale";
    private static final int DEFAULT_GUI_SCALE = 2;
    private static final int MAX_TARGET_GUI_SCALE = 8;

    public LumiUiScale {
        targetGuiScale = clamp(targetGuiScale, 1, MAX_TARGET_GUI_SCALE);
    }

    public static LumiUiScale current() {
        return new LumiUiScale(Integer.getInteger(
                TARGET_GUI_SCALE_PROPERTY, DEFAULT_GUI_SCALE));
    }

    public float renderScale(int currentGuiScale) {
        return targetGuiScale / (float) Math.max(1, currentGuiScale);
    }

    public int virtualSize(int framebufferSize) {
        return Math.max(1, (int) Math.ceil(framebufferSize / (double) targetGuiScale));
    }

    public double virtualCoordinate(double coordinate, int currentGuiScale) {
        return coordinate * Math.max(1, currentGuiScale) / targetGuiScale;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
