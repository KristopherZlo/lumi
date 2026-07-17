package io.github.lumi.client.ui;

import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Bounded native branch list with the legacy create, switch and merge actions. */
public final class LumiBranchesScreen extends LumiLegacyPageScreen {
    private static final int MAX_ROWS = 6;
    private final List<HistorySnapshotPayload.Branch> branches;
    private final Runnable create;
    private final Runnable merge;
    private final Consumer<String> switcher;
    private LegacyModalLayout layout;
    private int page;
    private String error = "";

    public LumiBranchesScreen(
            Screen parent,
            List<HistorySnapshotPayload.Branch> branches,
            Runnable create,
            Runnable merge,
            Consumer<String> switcher) {
        super(parent, Component.translatable("luma.variants.overview_title"),
                LegacyProjectTab.VARIANTS);
        this.branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        this.create = Objects.requireNonNull(create, "create");
        this.merge = Objects.requireNonNull(merge, "merge");
        this.switcher = Objects.requireNonNull(switcher, "switcher");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        LegacyWorkspaceLayout shell = pageLayout();
        layout = new LegacyModalLayout(
                shell.contentX(), shell.windowY(),
                shell.contentWidth(), shell.windowHeight());
        int x = layout.x();
        int y = layout.y();
        int contentWidth = Math.max(0, layout.width() - 32);
        int actionWidth = Math.max(0, (contentWidth - 8) / 2);
        addLegacyButton(x + 16, y + 38, actionWidth,
                Component.translatable("luma.action.variant_create"), create,
                LumiLegacyButton.Kind.PRIMARY);
        addLegacyButton(x + 24 + actionWidth, y + 38, actionWidth,
                Component.translatable("luma.action.merge_into_current"), merge,
                LumiLegacyButton.Kind.NORMAL);

        int rows = visibleRows();
        int start = rows == 0 ? 0 : Math.min(page * rows, branches.size());
        int end = Math.min(start + rows, branches.size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.Branch branch = branches.get(index);
            if (!branch.active()) {
                int rowY = y + 70 + (index - start) * 30;
                addLegacyIconButton(x + layout.width() - 48, rowY + 3,
                        "join", Component.translatable("luma.action.variant_switch"),
                        () -> switchBranch(branch.name()), LumiLegacyButton.Kind.PRIMARY);
            }
        }

        int footerY = y + layout.height() - 28;
        LumiLegacyButton previous = addLegacyIconButton(
                x + 16, footerY, "chevron-left", Component.literal("<"),
                () -> changePage(-1), LumiLegacyButton.Kind.NORMAL);
        previous.active = page > 0;
        LumiLegacyButton next = addLegacyIconButton(
                x + 48, footerY, "chevron-right", Component.literal(">"),
                () -> changePage(1), LumiLegacyButton.Kind.NORMAL);
        next.active = rows > 0 && (page + 1) * rows < branches.size();
        addLegacyIconButton(x + layout.width() - 42, footerY, "close",
                Component.translatable("luma.action.close"), this::onClose,
                LumiLegacyButton.Kind.NORMAL);
    }

    private void switchBranch(String name) {
        try {
            switcher.accept(name);
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("luma.status.variant_switched"), true);
            }
            onClose();
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi branch could not be switched" : failed.getMessage();
        }
    }

    private void changePage(int delta) {
        page = Math.max(0, page + delta);
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        renderLegacyPage(graphics, layout.x(), layout.y(), layout.width(), layout.height());
        graphics.drawString(font, title, layout.x() + 16, layout.y() + 14,
                LegacyLumiTheme.TEXT, false);
        int rows = visibleRows();
        int start = rows == 0 ? 0 : Math.min(page * rows, branches.size());
        int end = Math.min(start + rows, branches.size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.Branch branch = branches.get(index);
            int rowY = layout.y() + 70 + (index - start) * 30;
            renderLegacyPanel(graphics, layout.x() + 16, rowY, layout.width() - 32, 26);
            graphics.drawString(font,
                    font.plainSubstrByWidth(shortName(branch.name()), layout.width() - 86),
                    layout.x() + 24, rowY + 9,
                    branch.active() ? LegacyLumiTheme.ACCENT : LegacyLumiTheme.TEXT, false);
        }
        if (branches.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("luma.merge.no_sources"),
                    layout.x() + layout.width() / 2,
                    layout.y() + 86, LegacyLumiTheme.MUTED);
        }
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, errorText(error),
                    layout.x() + layout.width() / 2,
                    layout.y() + layout.height() - 44, LegacyLumiTheme.DANGER);
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private int visibleRows() {
        return Math.min(MAX_ROWS, Math.max(0, (layout.height() - 100) / 30));
    }

    private static String shortName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    @Override public boolean isPauseScreen() { return false; }
}
