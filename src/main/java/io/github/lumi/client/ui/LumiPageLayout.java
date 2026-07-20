package io.github.lumi.client.ui;

/** Project-window geometry shared by V2 screens. */
public record LumiPageLayout(
        int windowX,
        int windowY,
        int windowWidth,
        int windowHeight,
        int sidebarWidth,
        int titleHeight) {
    private static final int MARGIN = 10;
    private static final int WIDE_SIDEBAR = 172;
    private static final int NARROW_SIDEBAR = 136;

    public static LumiPageLayout fit(int screenWidth, int screenHeight) {
        int width = Math.max(1, screenWidth - MARGIN * 2);
        int height = Math.max(1, screenHeight - MARGIN * 2);
        int sidebar = screenWidth < 720 ? NARROW_SIDEBAR : WIDE_SIDEBAR;
        return new LumiPageLayout(
                Math.max(0, (screenWidth - width) / 2),
                Math.max(0, (screenHeight - height) / 2),
                width,
                height,
                Math.min(sidebar, Math.max(1, width / 2)),
                Math.min(LumiTheme.PAGE_HEADER_HEIGHT, height));
    }

    public int contentX() {
        return windowX + sidebarWidth;
    }

    public int contentWidth() {
        return windowWidth - sidebarWidth;
    }

    public int bodyX() {
        return contentX() + 6;
    }

    public int bodyY() {
        return windowY + titleHeight + 6;
    }

    public int bodyWidth() {
        return Math.max(0, contentWidth() - 12);
    }

    public int bodyHeight() {
        return Math.max(0, windowHeight - titleHeight - 12);
    }
}
