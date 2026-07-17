package io.github.lumi.client.ui;

/** Centered legacy modal geometry with safe margins on small windows. */
public record LegacyModalLayout(int x, int y, int width, int height) {
    private static final int MARGIN = 10;
    private static final int MAX_WIDTH = 320;

    public static LegacyModalLayout fit(
            int screenWidth, int screenHeight, int desiredHeight) {
        int width = Math.min(MAX_WIDTH, Math.max(0, screenWidth - MARGIN * 2));
        int height = Math.min(desiredHeight, Math.max(0, screenHeight - MARGIN * 2));
        return new LegacyModalLayout(
                Math.max(0, (screenWidth - width) / 2),
                Math.max(0, (screenHeight - height) / 2),
                width,
                height);
    }
}
