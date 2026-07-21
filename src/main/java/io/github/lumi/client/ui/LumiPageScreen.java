package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** One project page with shared shell and its own sidebar widgets. */
abstract class LumiPageScreen extends LumiScreen {
    private static final int SUPPORT_PANEL_OFFSET = 111;
    private static final int SUPPORT_CREDIT_OFFSET = 23;
    private static final int SIDEBAR_BUTTON_STRIDE = 19;
    private static final int ICON_BUTTON_WIDTH = 26;
    private static final java.net.URI COFFEE_URI =
            java.net.URI.create("https://buymeacoffee.com/zl0yxp");
    private static final java.net.URI PAYPAL_URI = java.net.URI.create(
            "https://www.paypal.com/donate/?hosted_button_id=CY7A2U64JWY4W");
    private static final java.net.URI BUG_URI =
            java.net.URI.create("https://github.com/KristopherZlo/lumi/issues/new");
    private final Screen parent;
    private final LumiPageSession pageSession;
    private final ProjectTab tab;
    private final String modVersion;
    private LumiPageLayout shellLayout;

    protected LumiPageScreen(
            Screen parent, Component title, ProjectTab tab) {
        this(parent, title, tab, ((LumiPageScreen) parent).pageSession);
    }

    protected LumiPageScreen(
            Screen parent, Component title, ProjectTab tab,
            LumiPageSession pageSession) {
        super(title);
        this.parent = parent;
        this.pageSession = pageSession;
        this.tab = tab;
        modVersion = FabricLoader.getInstance().getModContainer(LumiMod.MOD_ID)
                .map(container -> container.getMetadata()
                        .getVersion().getFriendlyString())
                .orElse("?");
    }

    protected final LumiPageLayout pageLayout() {
        return LumiPageLayout.fit(width, height);
    }

    @Override
    protected void afterScreenInit() {
        shellLayout = pageLayout();
        addSidebarButtons();
        addSupportButtons();
    }

    @Override
    protected void renderScaledUnderlay(GuiGraphics graphics) {
        graphics.fill(0, 0, width, height, LumiTheme.BACKDROP);
        drawShell(graphics);
    }

    private void addSidebarButtons() {
        if (compactSidebar()) {
            addCompactSidebarButtons();
            return;
        }
        int x = shellLayout.windowX() + 12;
        int width = shellLayout.sidebarWidth() - 24;
        int y = shellLayout.windowY() + 102;
        Integer zoneColor = activeZoneColor().orElse(null);
        addPageButton(x, y, width, "luma.tab.history", ProjectTab.HISTORY, null);
        addPageButton(x, y + SIDEBAR_BUTTON_STRIDE, width,
                "luma.tab.zones", ProjectTab.ZONES, zoneColor);
        addPageButton(x, y + SIDEBAR_BUTTON_STRIDE * 2, width,
                "luma.tab.variants", ProjectTab.VARIANTS, zoneColor);
        addPageButton(x, y + SIDEBAR_BUTTON_STRIDE * 3, width,
                "luma.tab.compare", ProjectTab.COMPARE, null);
        addPageButton(x, y + SIDEBAR_BUTTON_STRIDE * 4, width,
                "luma.tab.import_export", ProjectTab.IMPORT_EXPORT, null);
        addPageButton(x, y + SIDEBAR_BUTTON_STRIDE * 5, width,
                "luma.action.settings", ProjectTab.SETTINGS, null);
        addPageButton(x, y + SIDEBAR_BUTTON_STRIDE * 6, width,
                "luma.action.more", ProjectTab.MORE, null);
    }

    private void addPageButton(
            int x, int y, int width, String translation,
            ProjectTab destination, Integer accent) {
        addRenderableWidget(new LumiButton(
                x, y, width, 20, Component.translatable(translation),
                ignored -> pageSession.open(destination), tabKind(destination),
                null, accent));
    }

    private void addCompactSidebarButtons() {
        Integer zoneColor = activeZoneColor().orElse(null);
        addCompactSidebarButton(0, "graph", "luma.tab.history",
                ProjectTab.HISTORY, null);
        addCompactSidebarButton(1, "bookmarks", "luma.tab.zones",
                ProjectTab.ZONES, zoneColor);
        addCompactSidebarButton(2, "branch", "luma.tab.variants",
                ProjectTab.VARIANTS, zoneColor);
        addCompactSidebarButton(3, "see-changes", "luma.tab.compare",
                ProjectTab.COMPARE, null);
        addCompactSidebarButton(4, "folder", "luma.tab.import_export",
                ProjectTab.IMPORT_EXPORT, null);
        addCompactSidebarButton(5, "sliders", "luma.action.settings",
                ProjectTab.SETTINGS, null);
        addCompactSidebarButton(6, "unordered-list", "luma.action.more",
                ProjectTab.MORE, null);
    }

    private void addCompactSidebarButton(
            int index, String icon, String translation,
            ProjectTab destination, Integer accent) {
        addRenderableWidget(new LumiButton(
                compactSidebarActionX(shellLayout, index),
                compactSidebarActionY(shellLayout, index),
                compactSidebarActionWidth(shellLayout), 20,
                Component.translatable(translation),
                ignored -> pageSession.open(destination), tabKind(destination),
                icon, accent));
    }

    private void addSupportButtons() {
        int x = shellLayout.windowX() + 16;
        int y = supportTop(shellLayout) + 17;
        int width = shellLayout.sidebarWidth() - 32;
        addSupportButton(x, y, width, "buymeacoffee",
                "luma.action.buy_me_a_coffee", COFFEE_URI);
        addSupportButton(x, y + 20, width, "paypal",
                "luma.action.paypal_donate", PAYPAL_URI);
        addSupportButton(x, y + 40, width, "bug",
                "luma.action.report_bug", BUG_URI);
    }

    private void addSupportButton(
            int x, int y, int width, String icon,
            String translation, java.net.URI uri) {
        addRenderableWidget(new LumiButton(
                x, y, width, 20, Component.translatable(translation),
                ignored -> Util.getPlatform().openUri(uri),
                LumiButton.Kind.NORMAL, icon));
    }

    private void drawShell(GuiGraphics graphics) {
        int x = shellLayout.windowX();
        int y = shellLayout.windowY();
        int right = x + shellLayout.windowWidth();
        int bottom = y + shellLayout.windowHeight();
        alignNavigation(x, y, shellLayout.windowWidth());
        graphics.fill(x, y, right, bottom,
                activeZoneColor().orElse(LumiTheme.WINDOW_BORDER));
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1, LumiTheme.WINDOW);
        graphics.fill(x + 1, y + 1, shellLayout.contentX(), bottom - 1,
                LumiTheme.SIDEBAR);
        graphics.fill(shellLayout.contentX(), y + 1, right - 1,
                y + shellLayout.titleHeight(), LumiTheme.TITLEBAR);
        graphics.fill(shellLayout.contentX(), y + 1,
                shellLayout.contentX() + 1, bottom - 1,
                LumiTheme.WINDOW_BORDER);
        int headerBottom = y + shellLayout.titleHeight();
        graphics.fill(shellLayout.contentX() + 1, headerBottom - 1,
                right - 1, headerBottom, LumiTheme.PANEL_BORDER);
        graphics.drawString(font, "Lumi", x + 14, y + 18,
                LumiTheme.TEXT, false);
        if (!tinySidebar(shellLayout)) {
            graphics.drawString(font, Component.translatable("luma.window.mode"),
                    x + 14, y + 43, LumiTheme.MUTED, false);
        }
        int supportY = supportTop(shellLayout);
        int creditY = supportCreditY(shellLayout);
        LumiTheme.outlined(
                graphics, x + 10, supportY,
                shellLayout.sidebarWidth() - 20, creditY - 4 - supportY,
                LumiTheme.PANEL, LumiTheme.PANEL_BORDER);
        graphics.drawString(font, Component.translatable("luma.window.support"),
                x + 16, supportY + 7, LumiTheme.MUTED, false);
        int footerWidth = Math.max(1, shellLayout.sidebarWidth() - 32);
        graphics.drawString(font, font.plainSubstrByWidth(
                        Component.translatable("luma.window.credit").getString(),
                        footerWidth),
                x + 16, creditY, LumiTheme.MUTED, false);
        graphics.drawString(font, font.plainSubstrByWidth(
                        Component.translatable(
                                "luma.window.mod_version", modVersion).getString(),
                        footerWidth),
                x + 16, creditY + 11, LumiTheme.MUTED, false);
        pageSession.snapshot().ifPresent(snapshot -> drawProject(graphics, snapshot));
    }

    private void drawProject(
            GuiGraphics graphics, HistorySnapshotPayload snapshot) {
        int x = shellLayout.windowX();
        int y = shellLayout.windowY();
        if (!compactSidebar()) {
            drawChip(graphics, x + 14, y + 62,
                    shortDimension(displayedDimensionId(snapshot)));
            drawChip(graphics, x + 14, y + 84,
                    shortBranch(snapshot.branchName()));
        }
        if (tab == ProjectTab.HISTORY && rendersProjectHeader()) {
            renderPageHeader(
                    graphics, shellLayout.contentX(), y,
                    shellLayout.contentWidth(),
                    Component.translatable(
                            "luma.screen.project.title", snapshot.workspaceName()),
                    Component.translatable("luma.window.home_help"));
        }
    }

    protected String displayedDimensionId(HistorySnapshotPayload snapshot) {
        return snapshot.dimensionId();
    }

    protected boolean rendersProjectHeader() {
        return true;
    }

    private void drawChip(GuiGraphics graphics, int x, int y, String text) {
        int chipWidth = Math.min(
                shellLayout.sidebarWidth() - 28, font.width(text) + 12);
        LumiTheme.outlined(graphics, x, y, chipWidth, 17,
                LumiTheme.CHIP, LumiTheme.CHIP_BORDER);
        graphics.drawString(font, text, x + 6, y + 5,
                LumiTheme.MUTED, false);
    }

    private LumiButton.Kind tabKind(ProjectTab candidate) {
        return tab == candidate
                ? LumiButton.Kind.SELECTED : LumiButton.Kind.NORMAL;
    }

    private Optional<Integer> activeZoneColor() {
        return pageSession.snapshot().stream()
                .flatMap(snapshot -> snapshot.zones().stream())
                .filter(HistorySnapshotPayload.ZoneView::active)
                .map(HistorySnapshotPayload.ZoneView::color)
                .findFirst();
    }

    private boolean compactSidebar() {
        return shellLayout.sidebarWidth() < 136
                || shellLayout.windowHeight() < 350;
    }

    private static boolean tinySidebar(LumiPageLayout layout) {
        return layout.windowHeight() < 220;
    }

    static int compactSidebarActionX(LumiPageLayout layout, int index) {
        return layout.windowX() + 11 + (tinySidebar(layout)
                ? index * 16 : index % 4 * 28);
    }

    static int compactSidebarActionY(LumiPageLayout layout, int index) {
        return layout.windowY() + (tinySidebar(layout)
                ? 30 : 60 + index / 4 * 24);
    }

    static int compactSidebarActionWidth(LumiPageLayout layout) {
        return tinySidebar(layout) ? 14 : ICON_BUTTON_WIDTH;
    }

    static int supportTop(LumiPageLayout layout) {
        return layout.windowY() + layout.windowHeight() - SUPPORT_PANEL_OFFSET;
    }

    static int supportCreditY(LumiPageLayout layout) {
        return layout.windowY() + layout.windowHeight() - SUPPORT_CREDIT_OFFSET;
    }

    private static String shortBranch(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    static String shortDimension(String value) {
        return switch (value) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> {
                int separator = value.indexOf(':');
                yield separator < 0 ? value : value.substring(separator + 1);
            }
        };
    }

    protected final LumiPageSession pageSession() {
        return pageSession;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
