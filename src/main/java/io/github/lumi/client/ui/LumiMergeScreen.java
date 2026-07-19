package io.github.lumi.client.ui;

import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/** Explicit source-wins confirmation for merging another visible branch. */
public final class LumiMergeScreen extends LumiLegacyModalScreen {
    private static final int MAX_VISIBLE_BRANCHES = 6;
    private static final int PANEL_WIDTH = 520;
    private static final int PANEL_HEIGHT = 300;
    private final Screen parent;
    private final String dimensionId;
    private final String currentBranch;
    private final List<HistorySnapshotPayload.Branch> branches;
    private final ClientVersionPreviewStore previews;
    private final Consumer<String> merge;
    private HistorySnapshotPayload.Branch pendingSource;
    private String error = "";
    private int panelX;
    private int panelY;

    public LumiMergeScreen(
            Screen parent,
            HistorySnapshotPayload snapshot,
            ClientVersionPreviewStore previews,
            Consumer<String> merge) {
        this(parent, snapshot, snapshot.branches(), previews, merge);
    }

    public LumiMergeScreen(
            Screen parent,
            HistorySnapshotPayload snapshot,
            List<HistorySnapshotPayload.Branch> branches,
            ClientVersionPreviewStore previews,
            Consumer<String> merge) {
        super(Component.translatable("luma.action.merge_into_current"));
        this.parent = parent;
        this.currentBranch = shortName(
                Objects.requireNonNull(snapshot, "snapshot").branchName());
        this.dimensionId = snapshot.dimensionId();
        this.branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        this.previews = Objects.requireNonNull(previews, "previews");
        this.merge = Objects.requireNonNull(merge, "merge");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(16, (height - PANEL_HEIGHT) / 2);
        if (pendingSource != null) {
            addConfirmation(panelWidth);
            return;
        }
        int row = 0;
        for (HistorySnapshotPayload.Branch branch : branches) {
            if (branch.active() || row == MAX_VISIBLE_BRANCHES) continue;
            int rowY = panelY + 92 + row * 28;
            addLegacyButton(panelX + panelWidth - 168, rowY + 4, 148,
                    Component.translatable("luma.action.preview"),
                    () -> preview(branch), LumiLegacyButton.Kind.NORMAL);
            row++;
        }
        addLegacyButton(width / 2 - 60, panelY + PANEL_HEIGHT - 28, 120,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    private void addConfirmation(int panelWidth) {
        int buttonWidth = (panelWidth - 48) / 2;
        addLegacyButton(panelX + 20, panelY + PANEL_HEIGHT - 34, buttonWidth,
                Component.translatable("luma.action.merge_into_current"),
                () -> submit(pendingSource.name()),
                LumiLegacyButton.Kind.PRIMARY);
        addLegacyButton(panelX + 28 + buttonWidth,
                panelY + PANEL_HEIGHT - 34, buttonWidth,
                Component.translatable("luma.action.cancel"), () -> {
                    pendingSource = null;
                    error = "";
                    rebuildWidgets();
                }, LumiLegacyButton.Kind.NORMAL);
    }

    private void preview(HistorySnapshotPayload.Branch source) {
        pendingSource = source;
        error = "";
        rebuildWidgets();
    }

    private void submit(String source) {
        try {
            merge.accept(source);
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("luma.status.merge_started"), true);
            }
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi merge could not start" : failed.getMessage();
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
                Component.translatable("luma.merge.current", currentBranch),
                width / 2, panelY + 34, LegacyLumiTheme.MUTED);
        graphics.drawCenteredString(font,
                Component.translatable("luma.merge.source_wins"),
                width / 2, panelY + 52, LegacyLumiTheme.ACCENT);
        if (pendingSource != null) {
            renderConfirmation(graphics, panelWidth);
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
            return;
        }
        int row = 0;
        for (HistorySnapshotPayload.Branch branch : branches) {
            if (branch.active() || row == MAX_VISIBLE_BRANCHES) continue;
            int rowY = panelY + 92 + row * 28;
            renderLegacyPanel(graphics, panelX + 20, rowY,
                    panelWidth - 40, 28);
            graphics.drawString(font, shortName(branch.name()),
                    panelX + 28, rowY + 10, LegacyLumiTheme.TEXT, false);
            row++;
        }
        if (row == 0) {
            graphics.drawCenteredString(font,
                    Component.translatable("luma.merge.no_sources"),
                    width / 2, panelY + 108, LegacyLumiTheme.MUTED);
        }
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, errorText(error),
                    width / 2, panelY + 72, LegacyLumiTheme.DANGER);
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void renderConfirmation(GuiGraphics graphics, int panelWidth) {
        HistorySnapshotPayload.Branch target = branches.stream()
                .filter(HistorySnapshotPayload.Branch::active)
                .findFirst().orElse(null);
        int previewWidth = 128;
        int previewHeight = 72;
        int gap = 34;
        int startX = panelX + (panelWidth - previewWidth * 2 - gap) / 2;
        drawPreview(graphics, pendingSource, startX, panelY + 92,
                "luma.ideas.merge_source_preview",
                LegacyLumiTheme.ACCENT);
        if (target != null) {
            drawPreview(graphics, target,
                    startX + previewWidth + gap, panelY + 92,
                    "luma.ideas.merge_target_preview",
                    LegacyLumiTheme.TEXT);
        }
        graphics.drawCenteredString(
                font, Component.literal("→"),
                panelX + panelWidth / 2, panelY + 128,
                LegacyLumiTheme.ACCENT);
        graphics.drawCenteredString(
                font,
                Component.translatable(
                        "luma.merge.confirm_source",
                        shortName(pendingSource.name())),
                width / 2, panelY + 188, LegacyLumiTheme.TEXT);
        if (!error.isEmpty()) {
            graphics.drawCenteredString(
                    font, errorText(error), width / 2, panelY + 218,
                    LegacyLumiTheme.DANGER);
        }
    }

    private void drawPreview(
            GuiGraphics graphics,
            HistorySnapshotPayload.Branch branch,
            int x,
            int y,
            String label,
            int color) {
        graphics.drawCenteredString(
                font, Component.translatable(label),
                x + 64, y - 14, color);
        LegacyLumiTheme.outlined(
                graphics, x, y, 128, 72,
                LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
        previews.texture(dimensionId, branch.head()).ifPresent(texture ->
                graphics.blit(
                        RenderPipelines.GUI_TEXTURED, texture.id(),
                        x, y, 0, 0, 128, 72,
                        texture.width(), texture.height(),
                        texture.width(), texture.height()));
        graphics.drawCenteredString(
                font, shortName(branch.name()),
                x + 64, y + 78, color);
    }

    private static String shortName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
