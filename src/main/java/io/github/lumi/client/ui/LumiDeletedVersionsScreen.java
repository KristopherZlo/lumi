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
public final class LumiDeletedVersionsScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 520;
    private static final int PANEL_HEIGHT = 300;
    private static final int PAGE_SIZE = 5;
    private final Screen parent;
    private final ClientHistoryStore history;
    private final Consumer<CommitId> cleanup;
    private List<HistorySnapshotPayload.Version> versions = List.of();
    private int panelX;
    private int panelY;
    private int page;
    private String error = "";
    private HistorySnapshotPayload.Version pendingCleanup;

    public LumiDeletedVersionsScreen(
            Screen parent,
            ClientHistoryStore history,
            Consumer<CommitId> cleanup) {
        super(Component.translatable("luma.more.deleted_saves_title"));
        this.parent = parent;
        this.history = Objects.requireNonNull(history, "history");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        versions = history.state().snapshot()
                .map(HistorySnapshotPayload::deletedVersions).orElse(List.of());
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(8, (height - PANEL_HEIGHT) / 2);
        if (pendingCleanup != null) {
            addConfirmationButtons(panelWidth);
            return;
        }
        int start = Math.min(page * PAGE_SIZE, versions.size());
        int end = Math.min(start + PAGE_SIZE, versions.size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.Version version = versions.get(index);
            int rowY = panelY + 72 + (index - start) * 38;
            addLegacyButton(panelX + panelWidth - 112, rowY + 7, 92,
                    Component.translatable("luma.screen.cleanup.title"),
                    () -> confirm(version), LumiLegacyButton.Kind.DANGER);
        }
        LumiLegacyButton previous = addLegacyButton(
                panelX + 20, panelY + 262, 28, Component.literal("<"),
                () -> changePage(-1), LumiLegacyButton.Kind.NORMAL);
        previous.active = page > 0;
        LumiLegacyButton next = addLegacyButton(
                panelX + 52, panelY + 262, 28, Component.literal(">"),
                () -> changePage(1), LumiLegacyButton.Kind.NORMAL);
        next.active = (page + 1) * PAGE_SIZE < versions.size();
        addLegacyButton(panelX + panelWidth - 140, panelY + 262, 120,
                Component.translatable("luma.action.close"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    private void confirm(HistorySnapshotPayload.Version version) {
        pendingCleanup = version;
        error = "";
        rebuildWidgets();
    }

    private void addConfirmationButtons(int panelWidth) {
        int buttonWidth = (panelWidth - 48) / 2;
        addLegacyButton(panelX + 20, panelY + 246, buttonWidth,
                Component.translatable("luma.action.clean_up"),
                this::cleanup, LumiLegacyButton.Kind.DANGER);
        addLegacyButton(panelX + 28 + buttonWidth, panelY + 246, buttonWidth,
                Component.translatable("luma.action.cancel"), () -> {
                    pendingCleanup = null;
                    error = "";
                    rebuildWidgets();
                }, LumiLegacyButton.Kind.NORMAL);
    }

    private void cleanup() {
        try {
            cleanup.accept(pendingCleanup.id());
            feedback("luma.status.cleanup_applied");
            minecraft.setScreen(parent);
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
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        renderLegacyWindow(graphics, panelX, panelY, panelWidth, PANEL_HEIGHT);
        graphics.drawCenteredString(font, title, width / 2, panelY + 16,
                LegacyLumiTheme.TEXT);
        graphics.drawCenteredString(font,
                Component.translatable("luma.more.deleted_saves_help"),
                width / 2, panelY + 36, LegacyLumiTheme.MUTED);
        if (pendingCleanup == null) {
            renderVersions(graphics, panelWidth);
        } else {
            renderConfirmation(graphics, panelWidth);
        }
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, errorText(error),
                    width / 2, panelY + 220, LegacyLumiTheme.DANGER);
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
        int start = Math.min(page * PAGE_SIZE, versions.size());
        int end = Math.min(start + PAGE_SIZE, versions.size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.Version version = versions.get(index);
            int rowY = panelY + 72 + (index - start) * 38;
            renderLegacyPanel(graphics,
                    panelX + 20, rowY, panelWidth - 40, 34);
            graphics.drawString(font,
                    font.plainSubstrByWidth(version.message(), panelWidth - 180),
                    panelX + 28, rowY + 7, LegacyLumiTheme.TEXT, false);
            graphics.drawString(font, version.author(),
                    panelX + 28, rowY + 20, LegacyLumiTheme.MUTED, false);
        }
    }

    private void renderConfirmation(GuiGraphics graphics, int panelWidth) {
        renderLegacyPanel(graphics,
                panelX + 20, panelY + 66, panelWidth - 40, 134);
        graphics.drawCenteredString(font,
                Component.translatable("luma.screen.cleanup.title"),
                width / 2, panelY + 82, LegacyLumiTheme.DANGER);
        graphics.drawCenteredString(font,
                font.plainSubstrByWidth(pendingCleanup.message(), panelWidth - 80),
                width / 2, panelY + 108, LegacyLumiTheme.TEXT);
        graphics.drawCenteredString(font,
                Component.translatable("luma.recovery.delete_confirm_warning"),
                width / 2, panelY + 140, LegacyLumiTheme.ACCENT);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
