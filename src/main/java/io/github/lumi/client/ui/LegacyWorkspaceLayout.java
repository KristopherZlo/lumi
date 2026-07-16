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
    private static final int WIDE_SIDEBAR = 172;
    private static final int NARROW_SIDEBAR = 136;

    public static LegacyWorkspaceLayout fit(int screenWidth, int screenHeight) {
        int width = Math.max(320, screenWidth - MARGIN * 2);
        int height = Math.max(220, screenHeight - MARGIN * 2);
        return new LegacyWorkspaceLayout(
                Math.max(0, (screenWidth - width) / 2),
                Math.max(0, (screenHeight - height) / 2),
                width,
                height,
                screenWidth < 720 ? NARROW_SIDEBAR : WIDE_SIDEBAR,
                54);
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
        return contentWidth() - 28;
    }

    public int bodyHeight() {
        return windowHeight - titleHeight - 28;
    }
}
