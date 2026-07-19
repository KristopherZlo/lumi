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
    private static final int MAX_VISIBLE_ROWS = 5;
    private final ClientHistoryStore history;
    private final Consumer<CommitId> restore;
    private final Consumer<CommitId> cleanup;
    private List<HistorySnapshotPayload.Version> versions = List.of();
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int scroll;
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
        scroll = Math.min(scroll, Math.max(0, versions.size() - rows));
        int start = Math.min(scroll, versions.size());
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
    }

    private void confirm(HistorySnapshotPayload.Version version) {
        pendingCleanup = version;
        error = "";
        rebuildWidgets();
    }

    private void addConfirmationButtons() {
        int buttonWidth = (panelWidth - 48) / 2;
        int footerY = panelY + footerOffset(panelHeight);
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
                    panelY + (pendingCleanup == null
                            ? panelHeight - 44
                            : confirmationErrorOffset(panelHeight)),
                    LegacyLumiTheme.DANGER);
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
        int start = Math.min(scroll, versions.size());
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
        int top = confirmationPanelOffset(panelHeight);
        int footer = footerOffset(panelHeight);
        renderLegacyPanel(graphics,
                panelX + 20, panelY + top, panelWidth - 40,
                Math.max(1, footer - top - 8));
        graphics.drawCenteredString(font,
                Component.translatable("luma.screen.cleanup.title"),
                panelX + panelWidth / 2,
                panelY + confirmationHeadingOffset(panelHeight),
                LegacyLumiTheme.DANGER);
        graphics.drawCenteredString(font,
                font.plainSubstrByWidth(pendingCleanup.message(), panelWidth - 80),
                panelX + panelWidth / 2,
                panelY + confirmationMessageOffset(panelHeight),
                LegacyLumiTheme.TEXT);
        graphics.drawCenteredString(font,
                Component.translatable("luma.recovery.delete_confirm_warning"),
                panelX + panelWidth / 2,
                panelY + confirmationWarningOffset(panelHeight),
                LegacyLumiTheme.ACCENT);
    }

    static int footerOffset(int panelHeight) {
        return panelHeight - 28;
    }

    static int confirmationWarningOffset(int panelHeight) {
        return Math.min(140, panelHeight - 64);
    }

    static int confirmationErrorOffset(int panelHeight) {
        return confirmationWarningOffset(panelHeight) + 16;
    }

    static int confirmationMessageOffset(int panelHeight) {
        return Math.min(108, confirmationWarningOffset(panelHeight) - 16);
    }

    static int confirmationHeadingOffset(int panelHeight) {
        return Math.min(82, confirmationMessageOffset(panelHeight) - 16);
    }

    static int confirmationPanelOffset(int panelHeight) {
        return Math.min(66, confirmationHeadingOffset(panelHeight) - 8);
    }

    private int visibleRows() {
        return Math.min(
                MAX_VISIBLE_ROWS, Math.max(0, (panelHeight - 100) / 38));
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (pendingCleanup == null
                && x >= panelX && x < panelX + panelWidth
                && y >= panelY + 62 && y < panelY + panelHeight) {
            int maximum = Math.max(0, versions.size() - visibleRows());
            int replacement = Math.max(0, Math.min(
                    maximum, scroll + (verticalAmount < 0 ? 1 : -1)));
            if (replacement != scroll) {
                scroll = replacement;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(
                mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override public boolean isPauseScreen() { return false; }
}
