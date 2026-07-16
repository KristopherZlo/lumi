package io.github.lumi.client.ui;

import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Current workspace summary and bounded recent-version list. */
public final class LumiDashboardScreen extends Screen {
    private static final int MAX_VISIBLE_VERSIONS = 6;
    private final Screen parent;
    private final ClientHistoryStore history;
    private final Runnable openSave;
    private final Runnable openBranch;
    private final Runnable quickRollback;
    private final Consumer<HistorySnapshotPayload.Version> openRestore;
    private final Consumer<VersionCompareController.Target> openCompare;
    private final VersionCompareController compareController = new VersionCompareController();
    private HistorySnapshotPayload snapshot;
    private int panelX;
    private int panelY;
    private int panelWidth;

    public LumiDashboardScreen(
            Screen parent,
            ClientHistoryStore history,
            Runnable openSave,
            Runnable openBranch,
            Runnable quickRollback,
            Consumer<HistorySnapshotPayload.Version> openRestore,
            Consumer<VersionCompareController.Target> openCompare) {
        super(Component.translatable("luma.screen.dashboard.title"));
        this.parent = parent;
        this.history = Objects.requireNonNull(history, "history");
        this.openSave = Objects.requireNonNull(openSave, "openSave");
        this.openBranch = Objects.requireNonNull(openBranch, "openBranch");
        this.quickRollback = Objects.requireNonNull(quickRollback, "quickRollback");
        this.openRestore = Objects.requireNonNull(openRestore, "openRestore");
        this.openCompare = Objects.requireNonNull(openCompare, "openCompare");
    }

    @Override
    protected void init() {
        snapshot = history.state().snapshot().orElse(null);
        panelWidth = Math.min(520, width - 24);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(16, (height - 330) / 2);
        int buttonY = panelY + 72;
        int buttonWidth = (panelWidth - 64) / 4;
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.save_build"), ignored -> openSave.run())
                .bounds(panelX + 16, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.variant_create"), ignored -> openBranch.run())
                .bounds(panelX + 24 + buttonWidth, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("key.lumi.quick_rollback"), ignored -> {
                    quickRollback.run();
                    onClose();
                }).bounds(panelX + 32 + buttonWidth * 2, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.close"), ignored -> onClose())
                .bounds(panelX + 40 + buttonWidth * 3, buttonY, buttonWidth, 20).build());
        if (snapshot == null) {
            return;
        }
        int visible = Math.min(MAX_VISIBLE_VERSIONS, snapshot.versions().size());
        for (int index = 0; index < visible; index++) {
            int rowY = panelY + 128 + index * 32;
            HistorySnapshotPayload.Version version = snapshot.versions().get(index);
            compareController.target(snapshot.versions(), index).ifPresent(target ->
                    addRenderableWidget(Button.builder(
                            Component.translatable("luma.action.compare"),
                            ignored -> openCompare.accept(target))
                            .bounds(panelX + panelWidth - 168, rowY + 4, 72, 20).build()));
            addRenderableWidget(Button.builder(
                    Component.translatable("luma.action.restore"),
                    ignored -> openRestore.accept(version))
                    .bounds(panelX + panelWidth - 88, rowY + 4, 72, 20).build());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int panelHeight = Math.min(330, height - panelY - 16);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xee15181d);
        if (snapshot == null) {
            graphics.drawCenteredString(font,
                    Component.translatable("luma.dashboard.empty_title"),
                    width / 2, panelY + 36, 0xffffffff);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }
        graphics.drawString(font,
                Component.translatable("luma.screen.project.title", snapshot.workspaceName()),
                panelX + 16, panelY + 16, 0xffffffff, false);
        graphics.drawString(font,
                Component.translatable("luma.dashboard.current_dimension", snapshot.dimensionId()),
                panelX + 16, panelY + 34, 0xffaeb6c2, false);
        graphics.drawString(font,
                Component.translatable("luma.dashboard.active_branch", shortBranch()),
                panelX + 16, panelY + 48, 0xffaeb6c2, false);
        graphics.drawString(font,
                Component.translatable("luma.project.history_title"),
                panelX + 16, panelY + 108, 0xfff0f3f6, false);
        int visible = Math.min(MAX_VISIBLE_VERSIONS, snapshot.versions().size());
        for (int index = 0; index < visible; index++) {
            HistorySnapshotPayload.Version version = snapshot.versions().get(index);
            int rowY = panelY + 128 + index * 32;
            graphics.fill(panelX + 16, rowY, panelX + panelWidth - 16, rowY + 28,
                    0xff20252c);
            graphics.drawString(font,
                    font.plainSubstrByWidth(version.message(), panelWidth - 280),
                    panelX + 24, rowY + 5, 0xfff0f3f6, false);
            graphics.drawString(font, version.author(), panelX + 24, rowY + 17,
                    0xff8f9aa8, false);
        }
        if (snapshot.versions().isEmpty()) {
            graphics.drawString(font,
                    Component.translatable("luma.simple.no_saved_help"),
                    panelX + 16, panelY + 132, 0xff8f9aa8, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String shortBranch() {
        String value = snapshot.branchName();
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
