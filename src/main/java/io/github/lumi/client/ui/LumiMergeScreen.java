package io.github.lumi.client.ui;

import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit source-wins confirmation for merging another visible branch. */
public final class LumiMergeScreen extends LumiLegacyModalScreen {
    private static final int MAX_VISIBLE_BRANCHES = 6;
    private static final int PANEL_WIDTH = 520;
    private static final int PANEL_HEIGHT = 270;
    private final Screen parent;
    private final String currentBranch;
    private final List<HistorySnapshotPayload.Branch> branches;
    private final Consumer<String> merge;
    private String error = "";
    private int panelX;
    private int panelY;

    public LumiMergeScreen(
            Screen parent,
            String currentBranch,
            List<HistorySnapshotPayload.Branch> branches,
            Consumer<String> merge) {
        super(Component.translatable("luma.action.merge_into_current"));
        this.parent = parent;
        this.currentBranch = shortName(
                Objects.requireNonNull(currentBranch, "currentBranch"));
        this.branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        this.merge = Objects.requireNonNull(merge, "merge");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(16, (height - PANEL_HEIGHT) / 2);
        int row = 0;
        for (HistorySnapshotPayload.Branch branch : branches) {
            if (branch.active() || row == MAX_VISIBLE_BRANCHES) continue;
            int rowY = panelY + 92 + row * 28;
            addLegacyButton(panelX + panelWidth - 168, rowY + 4, 148,
                    Component.translatable("luma.action.merge_into_current"),
                    () -> submit(branch.name()), LumiLegacyButton.Kind.PRIMARY);
            row++;
        }
        addLegacyButton(width / 2 - 60, panelY + PANEL_HEIGHT - 28, 120,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
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

    private static String shortName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
