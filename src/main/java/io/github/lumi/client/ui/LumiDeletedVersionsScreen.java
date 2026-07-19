package io.github.lumi.client.ui;

import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Bounded tombstone list with an explicit legacy permanent-cleanup confirmation. */
public final class LumiDeletedVersionsScreen extends LumiLegacyPageScreen {
    private static final int MAX_PAGE_SIZE = 5;
    private final ClientHistoryStore history;
    private final Consumer<CommitId> restore;
    private final Consumer<CommitId> cleanup;
    private List<HistorySnapshotPayload.Version> versions = List.of();
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int page;
    private String error = "";
    private HistorySnapshotPayload.Version pendingCleanup;

    public LumiDeletedVersionsScreen(
            Screen parent,
            ClientHistoryStore history,
            Consumer<CommitId> restore,
            Consumer<CommitId> cleanup) {
        super(parent, Component.translatable("luma.more.deleted_saves_title"),
                LegacyProjectTab.MORE);
        this.history = Objects.requireNonNull(history, "history");
        this.restore = Objects.requireNonNull(restore, "restore");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        versions = history.state().snapshot()
                .map(HistorySnapshotPayload::deletedVersions).orElse(List.of());
        LegacyWorkspaceLayout shell = pageLayout();
        panelX = shell.contentX();
        panelY = shell.windowY();
        panelWidth = shell.contentWidth();
        panelHeight = shell.windowHeight();
        if (pendingCleanup != null) {
            addConfirmationButtons();
            return;
        }
        int rows = visibleRows();
        int start = rows == 0 ? 0 : Math.min(page * rows, versions.size());
        int end = Math.min(start + rows, versions.size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.Version version = versions.get(index);
            int rowY = panelY + 62 + (index - start) * 38;
            addLegacyIconButton(panelX + panelWidth - 76, rowY + 4,
                    "rollback", Component.translatable("luma.action.restore_deleted_save"),
                    () -> restoreVersion(version), LumiLegacyButton.Kind.PRIMARY);
            addLegacyIconButton(panelX + panelWidth - 48, rowY + 4,
                    "trash", Component.translatable("luma.screen.cleanup.title"),
                    () -> confirm(version), LumiLegacyButton.Kind.DANGER);
        }
        int footerY = panelY + panelHeight - 28;
        LumiLegacyButton previous = addLegacyButton(
                panelX + 20, footerY, 28, Component.literal("<"),
                () -> changePage(-1), LumiLegacyButton.Kind.NORMAL);
        previous.active = page > 0;
        LumiLegacyButton next = addLegacyButton(
                panelX + 52, footerY, 28, Component.literal(">"),
                () -> changePage(1), LumiLegacyButton.Kind.NORMAL);
        next.active = rows > 0 && (page + 1) * rows < versions.size();
        addLegacyButton(panelX + panelWidth - 140, footerY, 120,
                Component.translatable("luma.action.close"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    private void confirm(HistorySnapshotPayload.Version version) {
        pendingCleanup = version;
        error = "";
        rebuildWidgets();
    }

    private void addConfirmationButtons() {
        int buttonWidth = (panelWidth - 48) / 2;
        int footerY = panelY + panelHeight - 28;
        addLegacyButton(panelX + 20, footerY, buttonWidth,
                Component.translatable("luma.action.clean_up"),
                this::cleanup, LumiLegacyButton.Kind.DANGER);
        addLegacyButton(panelX + 28 + buttonWidth, footerY, buttonWidth,
                Component.translatable("luma.action.cancel"), () -> {
                    pendingCleanup = null;
                    error = "";
                    rebuildWidgets();
                }, LumiLegacyButton.Kind.NORMAL);
    }

    private void restoreVersion(HistorySnapshotPayload.Version version) {
        try {
            restore.accept(version.id());
            feedback("luma.status.version_restored");
            onClose();
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi could not restore the deleted save" : failed.getMessage();
        }
    }

    private void cleanup() {
        try {
            cleanup.accept(pendingCleanup.id());
            feedback("luma.status.cleanup_applied");
            onClose();
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi cleanup failed" : failed.getMessage();
        }
    }

    private void changePage(int delta) {
        page = Math.max(0, page + delta);
        rebuildWidgets();
    }

    private void feedback(String key) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(key), true);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        renderLegacyPage(graphics, panelX, panelY, panelWidth, panelHeight);
        graphics.drawCenteredString(font, title, panelX + panelWidth / 2, panelY + 16,
                LegacyLumiTheme.TEXT);
        graphics.drawCenteredString(font,
                Component.translatable("luma.more.deleted_saves_help"),
                panelX + panelWidth / 2, panelY + 36, LegacyLumiTheme.MUTED);
        if (pendingCleanup == null) {
            renderVersions(graphics, panelWidth);
        } else {
            renderConfirmation(graphics, panelWidth);
        }
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, errorText(error),
                    panelX + panelWidth / 2,
                    panelY + panelHeight - 44, LegacyLumiTheme.DANGER);
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void renderVersions(GuiGraphics graphics, int panelWidth) {
        if (versions.isEmpty()) {
            graphics.drawString(font,
                    Component.translatable("luma.more.deleted_saves_empty"),
                    panelX + 20, panelY + 78, LegacyLumiTheme.MUTED, false);
            return;
        }
        int rows = visibleRows();
        int start = rows == 0 ? 0 : Math.min(page * rows, versions.size());
        int end = Math.min(start + rows, versions.size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.Version version = versions.get(index);
            int rowY = panelY + 62 + (index - start) * 38;
            renderLegacyPanel(graphics,
                    panelX + 20, rowY, panelWidth - 40, 34);
            graphics.drawString(font,
                    font.plainSubstrByWidth(version.message(), panelWidth - 132),
                    panelX + 28, rowY + 7, LegacyLumiTheme.TEXT, false);
            graphics.drawString(font, version.author(),
                    panelX + 28, rowY + 20, LegacyLumiTheme.MUTED, false);
        }
    }

    private void renderConfirmation(GuiGraphics graphics, int panelWidth) {
        renderLegacyPanel(graphics,
                panelX + 20, panelY + 66, panelWidth - 40,
                Math.max(54, panelHeight - 114));
        graphics.drawCenteredString(font,
                Component.translatable("luma.screen.cleanup.title"),
                panelX + panelWidth / 2, panelY + 82, LegacyLumiTheme.DANGER);
        graphics.drawCenteredString(font,
                font.plainSubstrByWidth(pendingCleanup.message(), panelWidth - 80),
                panelX + panelWidth / 2, panelY + 108, LegacyLumiTheme.TEXT);
        graphics.drawCenteredString(font,
                Component.translatable("luma.recovery.delete_confirm_warning"),
                panelX + panelWidth / 2, panelY + 140, LegacyLumiTheme.ACCENT);
    }

    private int visibleRows() {
        return Math.min(MAX_PAGE_SIZE, Math.max(0, (panelHeight - 100) / 38));
    }

    @Override public boolean isPauseScreen() { return false; }
}
