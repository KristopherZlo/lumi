package io.github.lumi.client.ui;

import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Bounded tombstone list with native permanent-cleanup confirmation. */
public final class LumiDeletedVersionsScreen extends Screen {
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
        versions = history.state().snapshot()
                .map(HistorySnapshotPayload::deletedVersions).orElse(List.of());
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(8, (height - PANEL_HEIGHT) / 2);
        int start = Math.min(page * PAGE_SIZE, versions.size());
        int end = Math.min(start + PAGE_SIZE, versions.size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.Version version = versions.get(index);
            int rowY = panelY + 72 + (index - start) * 38;
            addRenderableWidget(Button.builder(
                    Component.translatable("luma.screen.cleanup.title"),
                    ignored -> confirm(version))
                    .bounds(panelX + panelWidth - 112, rowY + 7, 92, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("<"), ignored -> changePage(-1))
                .bounds(panelX + 20, panelY + 262, 28, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), ignored -> changePage(1))
                .bounds(panelX + 52, panelY + 262, 28, 20).build())
                .active = (page + 1) * PAGE_SIZE < versions.size();
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.close"), ignored -> onClose())
                .bounds(panelX + panelWidth - 140, panelY + 262, 120, 20).build());
    }

    private void confirm(HistorySnapshotPayload.Version version) {
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (!confirmed) {
                minecraft.setScreen(this);
                return;
            }
            try {
                cleanup.accept(version.id());
                feedback("luma.status.cleanup_applied");
                minecraft.setScreen(parent);
            } catch (RuntimeException failed) {
                error = failed.getMessage() == null
                        ? "Lumi cleanup failed" : failed.getMessage();
                minecraft.setScreen(this);
            }
        }, Component.translatable("luma.screen.cleanup.title"),
                Component.translatable("luma.recovery.delete_confirm_warning")));
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
        renderTransparentBackground(graphics);
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        graphics.fill(panelX, panelY, panelX + panelWidth,
                panelY + PANEL_HEIGHT, 0xee15181d);
        graphics.drawCenteredString(font, title, width / 2, panelY + 16, 0xffffffff);
        graphics.drawCenteredString(font,
                Component.translatable("luma.more.deleted_saves_help"),
                width / 2, panelY + 36, 0xffaeb6c2);
        renderVersions(graphics, panelWidth);
        if (!error.isEmpty()) {
            graphics.drawString(font, Component.literal(error),
                    panelX + 88, panelY + 268, 0xffff6b6b, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderVersions(GuiGraphics graphics, int panelWidth) {
        if (versions.isEmpty()) {
            graphics.drawString(font,
                    Component.translatable("luma.more.deleted_saves_empty"),
                    panelX + 20, panelY + 78, 0xff8f9aa8, false);
            return;
        }
        int start = Math.min(page * PAGE_SIZE, versions.size());
        int end = Math.min(start + PAGE_SIZE, versions.size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.Version version = versions.get(index);
            int rowY = panelY + 72 + (index - start) * 38;
            graphics.fill(panelX + 20, rowY,
                    panelX + panelWidth - 20, rowY + 34, 0xff20252c);
            graphics.drawString(font,
                    font.plainSubstrByWidth(version.message(), panelWidth - 180),
                    panelX + 28, rowY + 7, 0xfff0f3f6, false);
            graphics.drawString(font, version.author(),
                    panelX + 28, rowY + 20, 0xff8f9aa8, false);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
