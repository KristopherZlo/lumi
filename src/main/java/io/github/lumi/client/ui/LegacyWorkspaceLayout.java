package io.github.lumi.client.ui;

/** Legacy project-window geometry shared by retained V2 screens. */
public record LegacyWorkspaceLayout(
        int windowX,
        int windowY,
        int windowWidth,
        int windowHeight,
        int sidebarWidth,
        int titleHeight) {
    private static final int MARGIN = 10;
    private static final int MAX_WIDTH = 960;
    private static final int MAX_HEIGHT = 540;
    private static final int WIDE_SIDEBAR = 172;
    private static final int NARROW_SIDEBAR = 136;
    private static final int COMPACT_SIDEBAR = 112;

    public static LegacyWorkspaceLayout fit(int screenWidth, int screenHeight) {
        int width = Math.min(MAX_WIDTH, Math.max(1, screenWidth - MARGIN * 2));
        int height = Math.min(MAX_HEIGHT, Math.max(1, screenHeight - MARGIN * 2));
        int sidebar = width < 440
                ? COMPACT_SIDEBAR : width < 720 ? NARROW_SIDEBAR : WIDE_SIDEBAR;
        return new LegacyWorkspaceLayout(
                Math.max(0, (screenWidth - width) / 2),
                Math.max(0, (screenHeight - height) / 2),
                width,
                height,
                Math.min(sidebar, Math.max(1, width / 2)),
                Math.min(54, height));
    }

    public int contentX() {
        return windowX + sidebarWidth;
    }

    public int contentWidth() {
        return windowWidth - sidebarWidth;
    }

    public int bodyX() {
        return contentX() + 14;
    }

    public int bodyY() {
        return windowY + titleHeight + 14;
    }

    public int bodyWidth() {
        return Math.max(0, contentWidth() - 28);
    }

    public int bodyHeight() {
        return Math.max(0, windowHeight - titleHeight - 28);
    }
}
