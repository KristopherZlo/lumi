package io.github.lumi.client.ui;

import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Legacy project-window presentation backed by the immutable V2 history snapshot. */
public final class LumiDashboardScreen extends Screen {
    private static final int TEXT = 0xfff4f1ea;
    private static final int MUTED = 0xffa9a39a;
    private static final int ACCENT = 0xffd9b86c;
    private static final int BACKDROP = 0xd608090a;
    private static final int WINDOW = 0xff141517;
    private static final int WINDOW_BORDER = 0xff45413a;
    private static final int SIDEBAR = 0xff111214;
    private static final int TITLEBAR = 0xff1c1d20;
    private static final int PANEL = 0xef1a1b1e;
    private static final int PANEL_BORDER = 0xff343238;
    private static final int INSET = 0xea101113;
    private static final int CHIP = 0xff242326;
    private static final int CHIP_BORDER = 0xff3c3830;

    private final Screen parent;
    private final ClientHistoryStore history;
    private final Runnable openSave;
    private final Runnable openBranch;
    private final Runnable openMerge;
    private final Runnable openWorkspaces;
    private final Runnable openZones;
    private final Runnable openDeleted;
    private final Runnable openPackages;
    private final Runnable openMore;
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
            Runnable openSave,
            Runnable openBranch,
            Runnable openMerge,
            Runnable openWorkspaces,
            Runnable openZones,
            Runnable openDeleted,
            Runnable openPackages,
            Runnable openMore,
            Runnable quickRollback,
            Consumer<HistorySnapshotPayload.Version> openRestore,
            Consumer<HistorySnapshotPayload.Version> openDelete,
            Consumer<VersionCompareController.Target> openCompare) {
        super(Component.translatable("luma.screen.dashboard.title"));
        this.parent = parent;
        this.history = Objects.requireNonNull(history, "history");
        this.openSave = Objects.requireNonNull(openSave, "openSave");
        this.openBranch = Objects.requireNonNull(openBranch, "openBranch");
        this.openMerge = Objects.requireNonNull(openMerge, "openMerge");
        this.openWorkspaces = Objects.requireNonNull(openWorkspaces, "openWorkspaces");
        this.openZones = Objects.requireNonNull(openZones, "openZones");
        this.openDeleted = Objects.requireNonNull(openDeleted, "openDeleted");
        this.openPackages = Objects.requireNonNull(openPackages, "openPackages");
        this.openMore = Objects.requireNonNull(openMore, "openMore");
        this.quickRollback = Objects.requireNonNull(quickRollback, "quickRollback");
        this.openRestore = Objects.requireNonNull(openRestore, "openRestore");
        this.openDelete = Objects.requireNonNull(openDelete, "openDelete");
        this.openCompare = Objects.requireNonNull(openCompare, "openCompare");
    }

    @Override
    protected void init() {
        snapshot = history.state().snapshot().orElse(null);
        layout = LegacyWorkspaceLayout.fit(width, height);
        addSidebarButtons();
        int actionY = layout.bodyY() + 58;
        int x = layout.bodyX() + 14;
        addButton(x, actionY, 96, "luma.action.save_build", openSave,
                LumiLegacyButton.Kind.PRIMARY);
        addButton(x + 104, actionY, 88, "luma.action.variant_create", openBranch,
                LumiLegacyButton.Kind.NORMAL);
        addButton(x + 200, actionY, 104, "luma.action.merge_into_current", openMerge,
                LumiLegacyButton.Kind.NORMAL);
        addButton(x + 312, actionY, 112, "key.lumi.quick_rollback", () -> {
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
            compareController.target(snapshot.versions(), index).ifPresent(target ->
                    addButton(layout.bodyX() + layout.bodyWidth() - 210, rowY + 6,
                            58, "luma.action.compare",
                            () -> openCompare.accept(target), LumiLegacyButton.Kind.NORMAL));
            addButton(layout.bodyX() + layout.bodyWidth() - 146, rowY + 6,
                    58, "luma.action.restore",
                    () -> openRestore.accept(version), LumiLegacyButton.Kind.PRIMARY);
            addButton(layout.bodyX() + layout.bodyWidth() - 82, rowY + 6,
                    58, "luma.action.delete",
                    () -> openDelete.accept(version), LumiLegacyButton.Kind.DANGER);
        }
    }

    private void addSidebarButtons() {
        int x = layout.windowX() + 12;
        int width = layout.sidebarWidth() - 24;
        int y = layout.windowY() + 112;
        addButton(x, y, width, "luma.tab.history", () -> { },
                LumiLegacyButton.Kind.SELECTED);
        addButton(x, y + 27, width, "luma.tab.zones", openZones,
                LumiLegacyButton.Kind.NORMAL);
        addButton(x, y + 54, width, "luma.action.workspaces", openWorkspaces,
                LumiLegacyButton.Kind.NORMAL);
        addButton(x, y + 81, width, "luma.simple.share_button", openPackages,
                LumiLegacyButton.Kind.NORMAL);
        addButton(x, y + 108, width, "luma.more.deleted_saves_title", openDeleted,
                LumiLegacyButton.Kind.NORMAL);
        addButton(x, layout.windowY() + layout.windowHeight() - 36,
                width, "luma.action.more", openMore, LumiLegacyButton.Kind.NORMAL);
    }

    private void addButton(
            int x, int y, int width, String translation,
            Runnable action, LumiLegacyButton.Kind kind) {
        addRenderableWidget(new LumiLegacyButton(
                x, y, width, 20, Component.translatable(translation),
                ignored -> action.run(), kind));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKDROP);
        drawFrame(graphics);
        if (snapshot == null) {
            drawPanel(graphics, layout.bodyX(), layout.bodyY(),
                    layout.bodyWidth(), 96);
            graphics.drawString(font, Component.translatable("luma.dashboard.empty_title"),
                    layout.bodyX() + 14, layout.bodyY() + 16, TEXT, false);
            graphics.drawString(font, Component.translatable("luma.dashboard.empty"),
                    layout.bodyX() + 14, layout.bodyY() + 36, MUTED, false);
        } else {
            drawWorkspace(graphics);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawFrame(GuiGraphics graphics) {
        int x = layout.windowX();
        int y = layout.windowY();
        int right = x + layout.windowWidth();
        int bottom = y + layout.windowHeight();
        graphics.fill(x, y, right, bottom, WINDOW_BORDER);
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1, WINDOW);
        graphics.fill(x + 1, y + 1, layout.contentX(), bottom - 1, SIDEBAR);
        graphics.fill(layout.contentX(), y + 1, right - 1,
                y + layout.titleHeight(), TITLEBAR);
        graphics.drawString(font, "Lumi", x + 14, y + 18, TEXT, false);
        graphics.drawString(font, Component.translatable("luma.window.mode"),
                x + 14, y + 43, MUTED, false);
        if (snapshot != null) {
            drawChip(graphics, x + 14, y + 62,
                    shortDimension(snapshot.dimensionId()));
            drawChip(graphics, x + 14, y + 84, shortBranch());
            graphics.drawString(font,
                    Component.translatable("luma.screen.project.title",
                            snapshot.workspaceName()),
                    layout.contentX() + 16, y + 15, TEXT, false);
            graphics.drawString(font,
                    Component.translatable("luma.window.home_help"),
                    layout.contentX() + 16, y + 32, MUTED, false);
        }
    }

    private void drawWorkspace(GuiGraphics graphics) {
        int x = layout.bodyX();
        int width = layout.bodyWidth();
        drawPanel(graphics, x, layout.bodyY(), width, 90);
        graphics.drawString(font, Component.translatable("luma.project.build_title"),
                x + 14, layout.bodyY() + 13, TEXT, false);
        int pending = snapshot.pendingKeys();
        Component pendingText = pending == 0
                ? Component.translatable("luma.dashboard.pending_clean")
                : Component.translatable("luma.dashboard.workspace_pending", pending);
        graphics.drawString(font,
                pendingText,
                x + 14, layout.bodyY() + 31,
                pending == 0 ? MUTED : ACCENT, false);

        drawPanel(graphics, x, historyY, width, historyHeight);
        graphics.drawString(font, Component.translatable("luma.project.history_title"),
                x + 14, historyY + 13, TEXT, false);
        int rows = Math.min(snapshot.versions().size(),
                Math.max(0, (historyHeight - 50) / 34));
        for (int index = 0; index < rows; index++) {
            HistorySnapshotPayload.Version version = snapshot.versions().get(index);
            int rowY = historyY + 38 + index * 34;
            graphics.fill(x + 10, rowY, x + width - 10, rowY + 30, INSET);
            graphics.drawString(font,
                    font.plainSubstrByWidth(version.message(), width - 250),
                    x + 20, rowY + 5, TEXT, false);
            graphics.drawString(font, version.author(),
                    x + 20, rowY + 17, MUTED, false);
        }
        if (snapshot.versions().isEmpty()) {
            graphics.drawString(font,
                    Component.translatable("luma.simple.no_saved_help"),
                    x + 14, historyY + 38, MUTED, false);
        }
    }

    private static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL);
    }

    private void drawChip(GuiGraphics graphics, int x, int y, String text) {
        int width = Math.min(layout.sidebarWidth() - 28, font.width(text) + 12);
        graphics.fill(x, y, x + width, y + 17, CHIP_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 16, CHIP);
        graphics.drawString(font, text, x + 6, y + 5, MUTED, false);
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

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
