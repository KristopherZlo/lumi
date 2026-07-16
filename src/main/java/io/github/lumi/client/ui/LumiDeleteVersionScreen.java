package io.github.lumi.client.ui;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit confirmation for a durable soft-delete marker. */
public final class LumiDeleteVersionScreen extends Screen {
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 180;
    private final Screen parent;
    private final HistorySnapshotPayload.Version version;
    private final Consumer<CommitId> delete;
    private int panelX;
    private int panelY;
    private String error = "";

    public LumiDeleteVersionScreen(
            Screen parent,
            HistorySnapshotPayload.Version version,
            Consumer<CommitId> delete) {
        super(Component.translatable("luma.save_details.delete_title"));
        this.parent = parent;
        this.version = Objects.requireNonNull(version, "version");
        this.delete = Objects.requireNonNull(delete, "delete");
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        int buttonWidth = (panelWidth - 48) / 2;
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.delete_save"), ignored -> delete())
                .bounds(panelX + 20, panelY + 138, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.cancel"), ignored -> onClose())
                .bounds(panelX + 28 + buttonWidth, panelY + 138, buttonWidth, 20).build());
    }

    private void delete() {
        try {
            delete.accept(version.id());
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("luma.status.version_deleted"), true);
            }
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi save could not be deleted" : failed.getMessage();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        graphics.fill(panelX, panelY, panelX + panelWidth,
                panelY + PANEL_HEIGHT, 0xee15181d);
        graphics.drawCenteredString(font, title, width / 2, panelY + 18, 0xffffffff);
        graphics.drawCenteredString(font,
                font.plainSubstrByWidth(version.message(), panelWidth - 48),
                width / 2, panelY + 42, 0xfff0f3f6);
        graphics.drawCenteredString(font,
                Component.translatable("luma.save_details.delete_help"),
                width / 2, panelY + 68, 0xffaeb6c2);
        graphics.drawCenteredString(font,
                Component.translatable("luma.save_details.delete_warning"),
                width / 2, panelY + 90, 0xffffc857);
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(error),
                    width / 2, panelY + 116, 0xffff6b6b);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
