package io.github.lumi.client.ui;

import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit source-wins confirmation for merging another visible branch. */
public final class LumiMergeScreen extends Screen {
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
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(16, (height - PANEL_HEIGHT) / 2);
        int row = 0;
        for (HistorySnapshotPayload.Branch branch : branches) {
            if (branch.active() || row == MAX_VISIBLE_BRANCHES) continue;
            int rowY = panelY + 92 + row * 28;
            addRenderableWidget(Button.builder(
                    Component.translatable("luma.action.merge_into_current"),
                    ignored -> submit(branch.name()))
                    .bounds(panelX + panelWidth - 168, rowY + 4, 148, 20).build());
            row++;
        }
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.cancel"), ignored -> onClose())
                .bounds(width / 2 - 60, panelY + PANEL_HEIGHT - 28, 120, 20).build());
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
        renderTransparentBackground(graphics);
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        graphics.fill(panelX, panelY, panelX + panelWidth,
                panelY + PANEL_HEIGHT, 0xee15181d);
        graphics.drawCenteredString(font, title, width / 2, panelY + 16, 0xffffffff);
        graphics.drawCenteredString(font,
                Component.translatable("luma.merge.current", currentBranch),
                width / 2, panelY + 34, 0xffaeb6c2);
        graphics.drawCenteredString(font,
                Component.translatable("luma.merge.source_wins"),
                width / 2, panelY + 52, 0xffffc857);
        int row = 0;
        for (HistorySnapshotPayload.Branch branch : branches) {
            if (branch.active() || row == MAX_VISIBLE_BRANCHES) continue;
            int rowY = panelY + 92 + row * 28;
            graphics.fill(panelX + 20, rowY,
                    panelX + panelWidth - 20, rowY + 28, 0xff20252c);
            graphics.drawString(font, shortName(branch.name()),
                    panelX + 28, rowY + 10, 0xfff0f3f6, false);
            row++;
        }
        if (row == 0) {
            graphics.drawCenteredString(font,
                    Component.translatable("luma.merge.no_sources"),
                    width / 2, panelY + 108, 0xff8f9aa8);
        }
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(error),
                    width / 2, panelY + 72, 0xffff6b6b);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private static String shortName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
