package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/** Lumi coordinates resolved from Minecraft's live logical GUI scale. */
public record LumiUiScale(int targetGuiScale) {
    public static final String TARGET_GUI_SCALE_PROPERTY = "lumi.ui.targetGuiScale";
    private static final int MAX_TARGET_GUI_SCALE = 8;

    public LumiUiScale {
        targetGuiScale = clamp(targetGuiScale, 1, MAX_TARGET_GUI_SCALE);
    }

    public static LumiUiScale current() {
        Minecraft client = Minecraft.getInstance();
        Window window = client == null ? null : client.getWindow();
        return forGuiScale(window == null ? 2 : window.getGuiScale());
    }

    static LumiUiScale forGuiScale(int guiScale) {
        return new LumiUiScale(Integer.getInteger(
                TARGET_GUI_SCALE_PROPERTY, Math.max(1, guiScale)));
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
