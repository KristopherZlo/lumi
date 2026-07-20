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
    private static final int LIST_Y = 66;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_STRIDE = 21;
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
    private int panelWidth;
    private int panelHeight;
    private int sourceScroll;

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
        LegacyModalLayout layout = fitPanel(width, height);
        panelX = layout.x();
        panelY = layout.y();
        panelWidth = layout.width();
        panelHeight = layout.height();
        if (pendingSource != null) {
            addConfirmation();
            return;
        }
        List<HistorySnapshotPayload.Branch> sources = sourceBranches();
        int visibleRows = visibleBranchRows(panelHeight);
        sourceScroll = Math.min(sourceScroll,
                maximumSourceScroll(panelHeight, sources.size()));
        int buttonWidth = Math.min(148, Math.max(80, panelWidth / 3));
        int count = Math.min(visibleRows, sources.size() - sourceScroll);
        for (int row = 0; row < count; row++) {
            HistorySnapshotPayload.Branch branch = sources.get(sourceScroll + row);
            int rowY = panelY + LIST_Y + row * ROW_STRIDE;
            addLegacyButton(
                    panelX + panelWidth - buttonWidth - 18,
                    rowY + 1, buttonWidth,
                    Component.translatable("luma.action.preview"),
                    () -> preview(branch), LumiLegacyButton.Kind.NORMAL);
        }
        addLegacyButton(width / 2 - 60,
                panelY + actionOffset(panelHeight), 120,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    private void addConfirmation() {
        int buttonWidth = (panelWidth - 48) / 2;
        int actionY = panelY + actionOffset(panelHeight);
        addLegacyButton(panelX + 20, actionY, buttonWidth,
                Component.translatable("luma.action.merge_into_current"),
                () -> submit(pendingSource.name()),
                LumiLegacyButton.Kind.PRIMARY);
        addLegacyButton(panelX + 28 + buttonWidth,
                actionY, buttonWidth,
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
        renderLegacyWindow(graphics, panelX, panelY, panelWidth, panelHeight);
        int centerX = width / 2;
        int contentLeft = panelX + 20;
        int contentRight = panelX + panelWidth - 20;
        graphics.drawCenteredString(font, clippedCenteredHeader(
                title, centerX, contentLeft, contentRight),
                centerX, panelY + 16, LegacyLumiTheme.TEXT);
        graphics.drawCenteredString(font, clippedCenteredHeader(
                Component.translatable("luma.merge.current", currentBranch),
                centerX, contentLeft, contentRight),
                centerX, panelY + 34, LegacyLumiTheme.MUTED);
        if (pendingSource != null) {
            renderConfirmation(graphics);
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
            return;
        }
        graphics.drawCenteredString(font,
                Component.translatable("luma.merge.source_wins"),
                width / 2, panelY + 52, LegacyLumiTheme.ACCENT);
        List<HistorySnapshotPayload.Branch> sources = sourceBranches();
        int visibleRows = visibleBranchRows(panelHeight);
        int buttonWidth = Math.min(148, Math.max(80, panelWidth / 3));
        int count = Math.min(visibleRows, sources.size() - sourceScroll);
        for (int row = 0; row < count; row++) {
            HistorySnapshotPayload.Branch branch = sources.get(sourceScroll + row);
            int rowY = panelY + LIST_Y + row * ROW_STRIDE;
            renderLegacyPanel(graphics, panelX + 20, rowY,
                    panelWidth - 40, ROW_HEIGHT);
            graphics.drawString(font,
                    font.plainSubstrByWidth(
                            shortName(branch.name()),
                            panelWidth - buttonWidth - 54),
                    panelX + 28, rowY + 6, LegacyLumiTheme.TEXT, false);
        }
        renderLegacyScrollbar(
                graphics, panelX + panelWidth - 17, panelY + LIST_Y,
                Math.max(0, panelY + actionOffset(panelHeight) - 4
                        - (panelY + LIST_Y)),
                sources.size(), visibleRows, sourceScroll);
        if (sources.isEmpty()) {
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

    private void renderConfirmation(GuiGraphics graphics) {
        HistorySnapshotPayload.Branch target = branches.stream()
                .filter(HistorySnapshotPayload.Branch::active)
                .findFirst().orElse(null);
        int previewWidth = previewWidth(panelWidth);
        int previewHeight = previewHeight(panelHeight);
        int gap = 14;
        int previewY = panelY + 66;
        int startX = panelX + (panelWidth - previewWidth * 2 - gap) / 2;
        drawPreview(graphics, pendingSource, startX, previewY,
                previewWidth, previewHeight,
                "luma.ideas.merge_source_preview",
                LegacyLumiTheme.ACCENT);
        if (target != null) {
            drawPreview(graphics, target,
                    startX + previewWidth + gap, previewY,
                    previewWidth, previewHeight,
                    "luma.ideas.merge_target_preview",
                    LegacyLumiTheme.TEXT);
        }
        graphics.drawCenteredString(
                font, Component.literal("→"),
                panelX + panelWidth / 2,
                previewY + previewHeight / 2 - 4,
                LegacyLumiTheme.ACCENT);
        int actionY = panelY + actionOffset(panelHeight);
        graphics.drawCenteredString(
                font,
                font.plainSubstrByWidth(
                        Component.translatable(
                                "luma.merge.confirm_source",
                                shortName(pendingSource.name())).getString(),
                        Math.max(1, panelWidth - 40)),
                width / 2,
                Math.min(
                        panelY + confirmationTextOffset(
                                panelHeight, !error.isEmpty()),
                        previewY + previewHeight + 18),
                LegacyLumiTheme.TEXT);
        if (!error.isEmpty()) {
            graphics.drawCenteredString(
                    font, errorText(error), width / 2, actionY - 13,
                    LegacyLumiTheme.DANGER);
        }
    }

    private void drawPreview(
            GuiGraphics graphics,
            HistorySnapshotPayload.Branch branch,
            int x,
            int y,
            int previewWidth,
            int previewHeight,
            String label,
            int color) {
        graphics.drawCenteredString(
                font, font.plainSubstrByWidth(
                        Component.translatable(label).getString(),
                        Math.max(1, previewWidth - 4)),
                x + previewWidth / 2, y - 12, color);
        LegacyLumiTheme.outlined(
                graphics, x, y, previewWidth, previewHeight,
                LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
        previews.texture(dimensionId, branch.head()).ifPresent(texture ->
                graphics.blit(
                        RenderPipelines.GUI_TEXTURED, texture.id(),
                        x, y, 0, 0, previewWidth, previewHeight,
                        texture.width(), texture.height(),
                        texture.width(), texture.height()));
        graphics.drawCenteredString(
                font, font.plainSubstrByWidth(
                        shortName(branch.name()), Math.max(1, previewWidth - 8)),
                x + previewWidth / 2, y + previewHeight + 5, color);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (pendingSource == null
                && x >= panelX + 20 && x < panelX + panelWidth - 20
                && y >= panelY + LIST_Y
                && y < panelY + actionOffset(panelHeight)) {
            int maximum = maximumSourceScroll(
                    panelHeight, sourceBranches().size());
            int replacement = Math.max(0, Math.min(
                    maximum, sourceScroll + (verticalAmount < 0 ? 1 : -1)));
            if (replacement != sourceScroll) {
                sourceScroll = replacement;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(
                mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    static LegacyModalLayout fitPanel(int screenWidth, int screenHeight) {
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(1, screenWidth - 16));
        int panelHeight = Math.min(PANEL_HEIGHT, Math.max(1, screenHeight - 16));
        return new LegacyModalLayout(
                Math.max(0, (screenWidth - panelWidth) / 2),
                Math.max(0, (screenHeight - panelHeight) / 2),
                panelWidth, panelHeight);
    }

    static int actionOffset(int panelHeight) {
        return panelHeight - 28;
    }

    static int visibleBranchRows(int panelHeight) {
        return Math.min(MAX_VISIBLE_BRANCHES,
                Math.max(0,
                        (actionOffset(panelHeight) - LIST_Y - 4) / ROW_STRIDE));
    }

    static int listOffset() {
        return LIST_Y;
    }

    static int maximumSourceScroll(int panelHeight, int sourceCount) {
        return Math.max(0, sourceCount - visibleBranchRows(panelHeight));
    }

    static int previewWidth(int panelWidth) {
        return Math.min(128, Math.max(1, (panelWidth - 46) / 2));
    }

    static int previewHeight(int panelHeight) {
        return Math.min(72,
                Math.max(32, actionOffset(panelHeight) - 109));
    }

    static int confirmationTextOffset(
            int panelHeight, boolean errorVisible) {
        return actionOffset(panelHeight) - (errorVisible ? 24 : 14);
    }

    private static String shortName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    private List<HistorySnapshotPayload.Branch> sourceBranches() {
        return branches.stream()
                .filter(branch -> !branch.active())
                .toList();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
