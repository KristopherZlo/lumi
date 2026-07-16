package io.github.lumi.client.ui;

import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Full Restore confirmation with the retained durable-entity exclusion choice. */
public final class LumiRestoreScreen extends Screen {
    private static final int PANEL_WIDTH = 520;
    private static final int PANEL_HEIGHT = 166;
    private static final int PANEL_HEIGHT_WITH_SELECTION = 214;
    private final Screen parent;
    private final CommitId target;
    private final Optional<BlockBox> selection;
    private final BiConsumer<CommitId, Boolean> fullRestore;
    private final BiConsumer<CommitId, BlockAreaTarget> partialRestore;
    private String error = "";
    private int panelX;
    private int panelY;

    public LumiRestoreScreen(
            Screen parent,
            CommitId target,
            String message,
            Optional<BlockBox> selection,
            BiConsumer<CommitId, Boolean> fullRestore,
            BiConsumer<CommitId, BlockAreaTarget> partialRestore) {
        super(Component.translatable("luma.restore.confirm_title", message));
        this.parent = parent;
        this.target = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        this.selection = Objects.requireNonNull(selection, "selection");
        this.fullRestore = Objects.requireNonNull(fullRestore, "fullRestore");
        this.partialRestore = Objects.requireNonNull(partialRestore, "partialRestore");
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight()) / 2;
        int contentX = panelX + 20;
        int buttonWidth = (panelWidth - 48) / 2;
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.restore_whole_save"),
                ignored -> submitFull(true))
                .bounds(contentX, panelY + 124, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.restore_without_entities"),
                ignored -> submitFull(false))
                .bounds(contentX + buttonWidth + 8, panelY + 124, buttonWidth, 20).build());
        if (selection.isPresent()) {
            addRenderableWidget(Button.builder(
                    Component.translatable("luma.action.restore_only_selected_area"),
                    ignored -> submitArea(false))
                    .bounds(contentX, panelY + 152, buttonWidth, 20).build());
            addRenderableWidget(Button.builder(
                    Component.translatable("luma.action.restore_everything_except_selection"),
                    ignored -> submitArea(true))
                    .bounds(contentX + buttonWidth + 8, panelY + 152, buttonWidth, 20).build());
        }
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.cancel"), ignored -> onClose())
                .bounds(width / 2 - 60, panelY + panelHeight() - 30, 120, 20).build());
    }

    private void submitFull(boolean includeEntities) {
        try {
            fullRestore.accept(target, includeEntities);
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            showError(failed);
        }
    }

    private void submitArea(boolean outside) {
        try {
            partialRestore.accept(target,
                    new BlockAreaTarget(selection.orElseThrow(), outside));
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            showError(failed);
        }
    }

    private void showError(RuntimeException failed) {
        error = failed.getMessage() == null
                ? "Lumi Restore could not start" : failed.getMessage();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        graphics.fill(panelX, panelY, panelX + panelWidth,
                panelY + panelHeight(), 0xee15181d);
        graphics.drawCenteredString(font, title, width / 2, panelY + 17, 0xffffffff);
        graphics.drawCenteredString(font,
                Component.translatable("luma.restore.confirm_help"),
                width / 2, panelY + 42, 0xffaeb6c2);
        graphics.drawCenteredString(font,
                Component.translatable("luma.restore.confirm_safety"),
                width / 2, panelY + 58, 0xffaeb6c2);
        graphics.drawCenteredString(font,
                Component.translatable("luma.restore.entities_help"),
                width / 2, panelY + 78, 0xff8f9aa8);
        if (selection.isPresent()) {
            BlockBox box = selection.orElseThrow();
            graphics.drawCenteredString(font,
                    Component.literal(box.minX() + " " + box.minY() + " " + box.minZ()
                            + "  →  " + box.maxX() + " " + box.maxY() + " " + box.maxZ()),
                    width / 2, panelY + 96, 0xff8f9aa8);
        }
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(error),
                    width / 2, panelY + 110, 0xffff6b6b);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }

    private int panelHeight() {
        return selection.isPresent() ? PANEL_HEIGHT_WITH_SELECTION : PANEL_HEIGHT;
    }
}
