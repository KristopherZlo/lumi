package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Legacy project-window presentation backed by the immutable V2 history snapshot. */
public final class LumiDashboardScreen extends LumiLegacyModalScreen {
    private static final int PREVIEW_WIDTH = 40;
    private static final int PREVIEW_HEIGHT = 22;
    private static final int ICON_TEXTURE_SIZE = 24;
    private static final Identifier NO_PREVIEW_ICON = Identifier.fromNamespaceAndPath(
            LumiMod.MOD_ID, "textures/gui/new-icons/image.png");
    private final Screen parent;
    private final ClientHistoryStore history;
    private final ClientVersionPreviewStore previews;
    private final Runnable openSave;
    private final Runnable openAmend;
    private final Runnable openBranches;
    private final Runnable openWorkspaces;
    private final Runnable openZones;
    private final Runnable openDeleted;
    private final Runnable openPackages;
    private final Runnable openMore;
    private final Runnable openSettings;
    private final Runnable showChanges;
    private final Runnable quickRollback;
    private final Consumer<HistorySnapshotPayload.Version> openRestore;
    private final Consumer<HistorySnapshotPayload.Version> openDelete;
    private final Consumer<VersionCompareController.Target> openCompare;
    private final VersionCompareController compareController = new VersionCompareController();
    private HistorySnapshotPayload snapshot;
    private LegacyWorkspaceLayout layout;
    private int historyY;
    private int historyHeight;

    public LumiDashboardScreen(
            Screen parent,
            ClientHistoryStore history,
            ClientVersionPreviewStore previews,
            Runnable openSave,
            Runnable openAmend,
            Runnable openBranches,
            Runnable openWorkspaces,
            Runnable openZones,
            Runnable openDeleted,
            Runnable openPackages,
            Runnable openMore,
            Runnable openSettings,
            Runnable showChanges,
            Runnable quickRollback,
            Consumer<HistorySnapshotPayload.Version> openRestore,
            Consumer<HistorySnapshotPayload.Version> openDelete,
            Consumer<VersionCompareController.Target> openCompare) {
        super(Component.translatable("luma.screen.dashboard.title"));
        this.parent = parent;
        this.history = Objects.requireNonNull(history, "history");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.openSave = Objects.requireNonNull(openSave, "openSave");
        this.openAmend = Objects.requireNonNull(openAmend, "openAmend");
        this.openBranches = Objects.requireNonNull(openBranches, "openBranches");
        this.openWorkspaces = Objects.requireNonNull(openWorkspaces, "openWorkspaces");
        this.openZones = Objects.requireNonNull(openZones, "openZones");
        this.openDeleted = Objects.requireNonNull(openDeleted, "openDeleted");
        this.openPackages = Objects.requireNonNull(openPackages, "openPackages");
        this.openMore = Objects.requireNonNull(openMore, "openMore");
        this.openSettings = Objects.requireNonNull(openSettings, "openSettings");
        this.showChanges = Objects.requireNonNull(showChanges, "showChanges");
        this.quickRollback = Objects.requireNonNull(quickRollback, "quickRollback");
        this.openRestore = Objects.requireNonNull(openRestore, "openRestore");
        this.openDelete = Objects.requireNonNull(openDelete, "openDelete");
        this.openCompare = Objects.requireNonNull(openCompare, "openCompare");
    }

    @Override
    public void tick() {
        super.tick();
        HistorySnapshotPayload latest = history.state().snapshot().orElse(null);
        if (!Objects.equals(snapshot, latest)) {
            rebuildWidgets();
        }
    }

    @Override
    protected void init() {
        beginLegacyInit();
        snapshot = history.state().snapshot().orElse(null);
        layout = LegacyWorkspaceLayout.fit(width, height);
        addSidebarButtons();
        int actionY = layout.bodyY() + 58;
        int x = layout.bodyX() + 14;
        int available = Math.max(0, layout.bodyWidth() - 28);
        int buttonWidth = Math.max(0, (available - 70) / 2);
        addButton(x, actionY, buttonWidth, "luma.action.save_build", openSave,
                LumiLegacyButton.Kind.PRIMARY);
        addButton(x + buttonWidth + 6, actionY, buttonWidth,
                "luma.action.amend_version", openAmend,
                LumiLegacyButton.Kind.NORMAL);
        addIconButton(x + buttonWidth * 2 + 12, actionY,
                "see-changes", "luma.action.see_changes", showChanges,
                LumiLegacyButton.Kind.NORMAL);
        addIconButton(x + buttonWidth * 2 + 44, actionY,
                "rollback", "key.lumi.quick_rollback", () -> {
            quickRollback.run();
            onClose();
        }, LumiLegacyButton.Kind.DANGER);

        historyY = layout.bodyY() + 104;
        historyHeight = layout.bodyHeight() - 104;
        if (snapshot == null) {
            return;
        }
        int rows = Math.min(snapshot.versions().size(),
                Math.max(0, (historyHeight - 50) / 34));
        for (int index = 0; index < rows; index++) {
            HistorySnapshotPayload.Version version = snapshot.versions().get(index);
            int rowY = historyY + 38 + index * 34;
            int right = layout.bodyX() + layout.bodyWidth() - 14;
            compareController.target(snapshot.versions(), index).ifPresent(target ->
                    addIconButton(right - 90, rowY + 6,
                            "see-changes", "luma.action.compare",
                            () -> openCompare.accept(target), LumiLegacyButton.Kind.NORMAL));
            addIconButton(right - 58, rowY + 6,
                    "rollback", "luma.action.restore",
                    () -> openRestore.accept(version), LumiLegacyButton.Kind.PRIMARY);
            addIconButton(right - 26, rowY + 6,
                    "trash", "luma.action.delete",
                    () -> openDelete.accept(version), LumiLegacyButton.Kind.DANGER);
        }
    }

    private void addSidebarButtons() {
        if (compactSidebar()) {
            addCompactSidebarButtons();
            return;
        }
        int x = layout.windowX() + 12;
        int width = layout.sidebarWidth() - 24;
        int y = layout.windowY() + 112;
        addButton(x, y, width, "luma.tab.history", () -> { },
                LumiLegacyButton.Kind.SELECTED);
        addButton(x, y + 27, width, "luma.tab.zones", openZones,
                LumiLegacyButton.Kind.NORMAL);
        addButton(x, y + 54, width, "luma.tab.variants", openBranches,
                LumiLegacyButton.Kind.NORMAL);
        addButton(x, y + 81, width, "luma.action.workspaces", openWorkspaces,
                LumiLegacyButton.Kind.NORMAL);
        addButton(x, y + 108, width, "luma.simple.share_button", openPackages,
                LumiLegacyButton.Kind.NORMAL);
        addButton(x, y + 135, width, "luma.action.settings", openSettings,
                LumiLegacyButton.Kind.NORMAL);
        addButton(x, y + 162, width, "luma.more.deleted_saves_title", openDeleted,
                LumiLegacyButton.Kind.NORMAL);
        addButton(x, layout.windowY() + layout.windowHeight() - 36,
                width, "luma.action.more", openMore, LumiLegacyButton.Kind.NORMAL);
    }

    private void addCompactSidebarButtons() {
        int x = layout.windowX() + 12;
        int y = layout.windowY() + 60;
        addIconButton(x, y, "graph", "luma.tab.history", () -> { },
                LumiLegacyButton.Kind.SELECTED);
        addIconButton(x + 32, y, "sitemap-4", "luma.tab.zones", openZones,
                LumiLegacyButton.Kind.NORMAL);
        addIconButton(x, y + 26, "branch", "luma.tab.variants", openBranches,
                LumiLegacyButton.Kind.NORMAL);
        addIconButton(x + 32, y + 26, "bookmarks", "luma.action.workspaces",
                openWorkspaces, LumiLegacyButton.Kind.NORMAL);
        addIconButton(x, y + 52, "folder", "luma.simple.share_button", openPackages,
                LumiLegacyButton.Kind.NORMAL);
        addIconButton(x + 32, y + 52, "sliders", "luma.action.settings", openSettings,
                LumiLegacyButton.Kind.NORMAL);
        addIconButton(x, y + 78, "trash", "luma.more.deleted_saves_title", openDeleted,
                LumiLegacyButton.Kind.NORMAL);
        addIconButton(x + 32, y + 78, "unordered-list", "luma.action.more", openMore,
                LumiLegacyButton.Kind.NORMAL);
    }

    private void addButton(
            int x, int y, int width, String translation,
            Runnable action, LumiLegacyButton.Kind kind) {
        addRenderableWidget(new LumiLegacyButton(
                x, y, width, 20, Component.translatable(translation),
                ignored -> action.run(), kind));
    }

    private void addIconButton(
            int x, int y, String icon, String translation,
            Runnable action, LumiLegacyButton.Kind kind) {
        addLegacyIconButton(x, y, icon, Component.translatable(translation), action, kind);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        graphics.fill(0, 0, width, height, LegacyLumiTheme.BACKDROP);
        drawFrame(graphics);
        if (snapshot == null) {
            drawPanel(graphics, layout.bodyX(), layout.bodyY(),
                    layout.bodyWidth(), 96);
            graphics.drawString(font, Component.translatable("luma.dashboard.empty_title"),
                    layout.bodyX() + 14, layout.bodyY() + 16,
                    LegacyLumiTheme.TEXT, false);
            graphics.drawString(font, Component.translatable("luma.dashboard.empty"),
                    layout.bodyX() + 14, layout.bodyY() + 36,
                    LegacyLumiTheme.MUTED, false);
        } else {
            drawWorkspace(graphics);
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void drawFrame(GuiGraphics graphics) {
        int x = layout.windowX();
        int y = layout.windowY();
        int right = x + layout.windowWidth();
        int bottom = y + layout.windowHeight();
        graphics.fill(x, y, right, bottom, LegacyLumiTheme.WINDOW_BORDER);
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1,
                LegacyLumiTheme.WINDOW);
        graphics.fill(x + 1, y + 1, layout.contentX(), bottom - 1,
                LegacyLumiTheme.SIDEBAR);
        graphics.fill(layout.contentX(), y + 1, right - 1,
                y + layout.titleHeight(), LegacyLumiTheme.TITLEBAR);
        graphics.drawString(font, "Lumi", x + 14, y + 18,
                LegacyLumiTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("luma.window.mode"),
                x + 14, y + 43, LegacyLumiTheme.MUTED, false);
        if (snapshot != null) {
            if (!compactSidebar()) {
                drawChip(graphics, x + 14, y + 62,
                        shortDimension(snapshot.dimensionId()));
                drawChip(graphics, x + 14, y + 84, shortBranch());
            }
            graphics.drawString(font,
                    Component.translatable("luma.screen.project.title",
                            snapshot.workspaceName()),
                    layout.contentX() + 16, y + 15,
                    LegacyLumiTheme.TEXT, false);
            graphics.drawString(font,
                    Component.translatable("luma.window.home_help"),
                    layout.contentX() + 16, y + 32,
                    LegacyLumiTheme.MUTED, false);
        }
    }

    private void drawWorkspace(GuiGraphics graphics) {
        int x = layout.bodyX();
        int width = layout.bodyWidth();
        drawPanel(graphics, x, layout.bodyY(), width, 90);
        graphics.drawString(font, Component.translatable("luma.project.build_title"),
                x + 14, layout.bodyY() + 13, LegacyLumiTheme.TEXT, false);
        int pending = snapshot.pendingKeys();
        Component pendingText = pending == 0
                ? Component.translatable("luma.dashboard.pending_clean")
                : Component.translatable("luma.dashboard.workspace_pending", pending);
        graphics.drawString(font,
                pendingText,
                x + 14, layout.bodyY() + 31,
                pending == 0
                        ? LegacyLumiTheme.MUTED : LegacyLumiTheme.ACCENT,
                false);

        drawPanel(graphics, x, historyY, width, historyHeight);
        graphics.drawString(font, Component.translatable("luma.project.history_title"),
                x + 14, historyY + 13, LegacyLumiTheme.TEXT, false);
        int rows = Math.min(snapshot.versions().size(),
                Math.max(0, (historyHeight - 50) / 34));
        for (int index = 0; index < rows; index++) {
            HistorySnapshotPayload.Version version = snapshot.versions().get(index);
            int rowY = historyY + 38 + index * 34;
            graphics.fill(x + 10, rowY, x + width - 10, rowY + 30,
                    LegacyLumiTheme.INSET);
            drawPreview(graphics, version, x + 16, rowY + 4);
            graphics.drawString(font,
                    font.plainSubstrByWidth(
                            version.message(), Math.max(0, width - 180)),
                    x + 64, rowY + 5, LegacyLumiTheme.TEXT, false);
            graphics.drawString(font, version.author(),
                    x + 64, rowY + 17, LegacyLumiTheme.MUTED, false);
        }
        if (snapshot.versions().isEmpty()) {
            graphics.drawString(font,
                    Component.translatable("luma.simple.no_saved_help"),
                    x + 14, historyY + 38, LegacyLumiTheme.MUTED, false);
        }
    }

    private void drawPreview(
            GuiGraphics graphics, HistorySnapshotPayload.Version version, int x, int y) {
        var texture = previews.texture(snapshot.dimensionId(), version.id()).orElse(null);
        if (texture != null) {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED, texture.id(),
                    x, y, 0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                    texture.width(), texture.height(), texture.width(), texture.height());
            return;
        }
        LegacyLumiTheme.outlined(graphics, x, y, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                LegacyLumiTheme.WINDOW, LegacyLumiTheme.INSET_BORDER);
        int iconSize = 12;
        graphics.blit(
                RenderPipelines.GUI_TEXTURED, NO_PREVIEW_ICON,
                x + (PREVIEW_WIDTH - iconSize) / 2,
                y + (PREVIEW_HEIGHT - iconSize) / 2,
                0, 0, iconSize, iconSize,
                ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE,
                ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE);
    }

    private static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        LegacyLumiTheme.outlined(graphics, x, y, width, height,
                LegacyLumiTheme.PANEL, LegacyLumiTheme.PANEL_BORDER);
    }

    private void drawChip(GuiGraphics graphics, int x, int y, String text) {
        int width = Math.min(layout.sidebarWidth() - 28, font.width(text) + 12);
        LegacyLumiTheme.outlined(graphics, x, y, width, 17,
                LegacyLumiTheme.CHIP, LegacyLumiTheme.CHIP_BORDER);
        graphics.drawString(font, text, x + 6, y + 5,
                LegacyLumiTheme.MUTED, false);
    }

    private String shortBranch() {
        String value = snapshot.branchName();
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static String shortDimension(String value) {
        return switch (value) {
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> "Overworld";
        };
    }

    private boolean compactSidebar() {
        return layout.sidebarWidth() < 136 || layout.windowHeight() < 320;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
