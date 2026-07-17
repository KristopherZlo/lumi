package io.github.lumi.client.ui;

import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Full Restore confirmation with the retained durable-entity exclusion choice. */
public final class LumiRestoreScreen extends LumiLegacyModalScreen {
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
        beginLegacyInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight()) / 2;
        int contentX = panelX + 20;
        int buttonWidth = (panelWidth - 48) / 2;
        int firstRowY = panelY + (selection.isPresent() ? 116 : 98);
        addLegacyButton(contentX, firstRowY, buttonWidth,
                Component.translatable("luma.action.restore_whole_save"),
                () -> submitFull(true), LumiLegacyButton.Kind.PRIMARY);
        addLegacyButton(contentX + buttonWidth + 8, firstRowY, buttonWidth,
                Component.translatable("luma.action.restore_without_entities"),
                () -> submitFull(false), LumiLegacyButton.Kind.NORMAL);
        if (selection.isPresent()) {
            addLegacyButton(contentX, firstRowY + 28, buttonWidth,
                    Component.translatable("luma.action.restore_only_selected_area"),
                    () -> submitArea(false), LumiLegacyButton.Kind.NORMAL);
            addLegacyButton(contentX + buttonWidth + 8, firstRowY + 28, buttonWidth,
                    Component.translatable("luma.action.restore_everything_except_selection"),
                    () -> submitArea(true), LumiLegacyButton.Kind.NORMAL);
        }
        addLegacyButton(width / 2 - 60, panelY + panelHeight() - 28, 120,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
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
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        renderLegacyWindow(graphics, panelX, panelY, panelWidth, panelHeight());
        graphics.drawCenteredString(font, title, width / 2, panelY + 17,
                LegacyLumiTheme.TEXT);
        graphics.drawCenteredString(font,
                Component.translatable("luma.restore.confirm_help"),
                width / 2, panelY + 42, LegacyLumiTheme.MUTED);
        graphics.drawCenteredString(font,
                Component.translatable("luma.restore.confirm_safety"),
                width / 2, panelY + 58, LegacyLumiTheme.MUTED);
        graphics.drawCenteredString(font,
                Component.translatable("luma.restore.entities_help"),
                width / 2, panelY + 78, LegacyLumiTheme.MUTED);
        if (selection.isPresent()) {
            BlockBox box = selection.orElseThrow();
            graphics.drawCenteredString(font,
                    Component.literal(box.minX() + " " + box.minY() + " " + box.minZ()
                            + "  →  " + box.maxX() + " " + box.maxY() + " " + box.maxZ()),
                    width / 2, panelY + 96, 0xff8f9aa8);
        }
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, errorText(error),
                    width / 2, panelY + (selection.isPresent() ? 104 : 84),
                    LegacyLumiTheme.DANGER);
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }

    private int panelHeight() {
        return selection.isPresent() ? PANEL_HEIGHT_WITH_SELECTION : PANEL_HEIGHT;
    }
}
