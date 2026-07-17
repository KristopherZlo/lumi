package io.github.lumi.client.ui;

import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Bounded native picker for comparing any two saves in the current history. */
public final class LumiComparePickerScreen extends LumiLegacyPageScreen {
    private static final int MAX_ROWS = 6;
    private final List<HistorySnapshotPayload.Version> versions;
    private final Consumer<VersionCompareController.Target> compare;
    private final VersionCompareController controller = new VersionCompareController();
    private LegacyModalLayout layout;
    private int page;
    private int beforeIndex = -1;
    private int afterIndex = -1;

    public LumiComparePickerScreen(
            Screen parent,
            List<HistorySnapshotPayload.Version> versions,
            Consumer<VersionCompareController.Target> compare) {
        super(parent, Component.translatable("luma.compare.pick_title"),
                LegacyProjectTab.COMPARE);
        this.versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
        this.compare = Objects.requireNonNull(compare, "compare");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        LegacyWorkspaceLayout shell = pageLayout();
        layout = new LegacyModalLayout(
                shell.contentX(), shell.windowY(),
                shell.contentWidth(), shell.windowHeight());
        addRowButtons();

        int footerY = layout.y() + layout.height() - 28;
        LumiLegacyButton previous = addLegacyIconButton(
                layout.x() + 16, footerY, "chevron-left", Component.literal("<"),
                () -> changePage(-1), LumiLegacyButton.Kind.NORMAL);
        previous.active = page > 0;
        LumiLegacyButton next = addLegacyIconButton(
                layout.x() + 48, footerY, "chevron-right", Component.literal(">"),
                () -> changePage(1), LumiLegacyButton.Kind.NORMAL);
        next.active = visibleRows() > 0
                && (page + 1) * visibleRows() < versions.size();
        LumiLegacyButton submit = addLegacyButton(
                layout.x() + layout.width() - 256, footerY, 96,
                Component.translatable("luma.action.compare"),
                this::compareSelected, LumiLegacyButton.Kind.PRIMARY);
        submit.active = target().isPresent();
        addLegacyButton(
                layout.x() + layout.width() - 152, footerY, 136,
                Component.translatable("luma.action.close"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    private void addRowButtons() {
        int rows = visibleRows();
        int start = rows == 0 ? 0 : Math.min(page * rows, versions.size());
        int end = Math.min(start + rows, versions.size());
        for (int index = start; index < end; index++) {
            int selectedIndex = index;
            int rowY = layout.y() + 68 + (index - start) * 34;
            int fromX = layout.x() + layout.width() - 126;
            addLegacyButton(
                    fromX, rowY + 5, 50,
                    Component.translatable("luma.compare.left"),
                    () -> selectBefore(selectedIndex),
                    beforeIndex == index
                            ? LumiLegacyButton.Kind.SELECTED
                            : LumiLegacyButton.Kind.NORMAL);
            addLegacyButton(
                    fromX + 56, rowY + 5, 50,
                    Component.translatable("luma.compare.right"),
                    () -> selectAfter(selectedIndex),
                    afterIndex == index
                            ? LumiLegacyButton.Kind.SELECTED
                            : LumiLegacyButton.Kind.NORMAL);
        }
    }

    private void selectBefore(int index) {
        beforeIndex = index;
        rebuildWidgets();
    }

    private void selectAfter(int index) {
        afterIndex = index;
        rebuildWidgets();
    }

    private void compareSelected() {
        target().ifPresent(compare);
    }

    private java.util.Optional<VersionCompareController.Target> target() {
        return controller.target(versions, beforeIndex, afterIndex);
    }

    private void changePage(int delta) {
        page = Math.max(0, page + delta);
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            renderLegacyPage(
                    graphics, layout.x(), layout.y(), layout.width(), layout.height());
            graphics.drawString(font, title,
                    layout.x() + 16, layout.y() + 14,
                    LegacyLumiTheme.TEXT, false);
            graphics.drawString(font,
                    Component.translatable("luma.compare.pick_help"),
                    layout.x() + 16, layout.y() + 32,
                    LegacyLumiTheme.MUTED, false);
            renderRows(graphics);
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void renderRows(GuiGraphics graphics) {
        if (versions.isEmpty()) {
            graphics.drawString(font,
                    Component.translatable("luma.history.empty"),
                    layout.x() + 16, layout.y() + 74,
                    LegacyLumiTheme.MUTED, false);
            return;
        }
        int rows = visibleRows();
        int start = rows == 0 ? 0 : Math.min(page * rows, versions.size());
        int end = Math.min(start + rows, versions.size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.Version version = versions.get(index);
            int rowY = layout.y() + 68 + (index - start) * 34;
            renderLegacyPanel(
                    graphics, layout.x() + 16, rowY, layout.width() - 32, 30);
            graphics.drawString(font,
                    font.plainSubstrByWidth(
                            version.message(), Math.max(0, layout.width() - 174)),
                    layout.x() + 24, rowY + 6,
                    LegacyLumiTheme.TEXT, false);
            graphics.drawString(font, version.author(),
                    layout.x() + 24, rowY + 18,
                    LegacyLumiTheme.MUTED, false);
        }
    }

    private int visibleRows() {
        return Math.min(MAX_ROWS, Math.max(0, (layout.height() - 108) / 34));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
