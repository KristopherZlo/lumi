package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/** Stable 640x360 virtual viewport resolved from the physical framebuffer. */
public record LumiUiScale(int targetGuiScale) {
    public static final String TARGET_GUI_SCALE_PROPERTY = "lumi.ui.targetGuiScale";
    private static final int REFERENCE_WIDTH = 640;
    private static final int REFERENCE_HEIGHT = 360;
    private static final int MIN_TARGET_GUI_SCALE = 2;
    private static final int MAX_TARGET_GUI_SCALE = 8;

    public LumiUiScale {
        targetGuiScale = clamp(targetGuiScale, 1, MAX_TARGET_GUI_SCALE);
    }

    public static LumiUiScale current() {
        Minecraft client = Minecraft.getInstance();
        Window window = client == null ? null : client.getWindow();
        return window == null
                ? forFramebuffer(1280, 720)
                : forFramebuffer(window.getWidth(), window.getHeight());
    }

    public static LumiUiScale forFramebuffer(int width, int height) {
        int fittingScale = Math.min(
                Math.max(1, width) / REFERENCE_WIDTH,
                Math.max(1, height) / REFERENCE_HEIGHT);
        int automatic = fittingScale >= 8
                ? 8 : fittingScale >= 6 ? 6
                : clamp(fittingScale, MIN_TARGET_GUI_SCALE, 4);
        return new LumiUiScale(clamp(
                Integer.getInteger(TARGET_GUI_SCALE_PROPERTY, automatic),
                1, MAX_TARGET_GUI_SCALE));
    }

    public float renderScale(int currentGuiScale) {
        return targetGuiScale / (float) Math.max(1, currentGuiScale);
    }

    public int virtualSize(int scaledSize, int currentGuiScale) {
        return Math.max(1, (int) (((long) scaledSize * Math.max(1, currentGuiScale))
                / targetGuiScale));
    }

    public double virtualCoordinate(double coordinate, int currentGuiScale) {
        return coordinate / renderScale(currentGuiScale);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
