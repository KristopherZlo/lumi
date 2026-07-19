package io.github.lumi.client.ui;

import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.PartialRestorePlanPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Preview-gated selected/outside Restore using only wooden-sword bounds. */
public final class LumiPartialRestoreScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 440;
    private static final int PANEL_HEIGHT = 190;
    private final Screen parent;
    private final CommitId target;
    private final BiFunction<CommitId, BlockAreaTarget, UUID> previewRestore;
    private final Function<UUID, UUID> applyRestore;
    private final PartialRestoreFormState form;
    private LumiLegacyButton preview;
    private LumiLegacyButton apply;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private String localError = "";

    public LumiPartialRestoreScreen(
            Screen parent,
            CommitId target,
            String message,
            BlockBox selection,
            BiFunction<CommitId, BlockAreaTarget, UUID> previewRestore,
            Function<UUID, UUID> applyRestore) {
        super(Component.translatable("luma.restore.confirm_title", message));
        this.parent = parent;
        this.target = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        this.previewRestore = Objects.requireNonNull(previewRestore, "previewRestore");
        this.applyRestore = Objects.requireNonNull(applyRestore, "applyRestore");
        form = new PartialRestoreFormState(
                target, Optional.of(Objects.requireNonNull(selection, "selection")));
    }

    @Override
    protected void init() {
        beginLegacyInit();
        LegacyModalLayout layout = fitPanel(width, height);
        panelX = layout.x();
        panelY = layout.y();
        panelWidth = layout.width();
        panelHeight = layout.height();
        int innerWidth = panelWidth - 32;
        int half = (innerWidth - 8) / 2;
        addLegacyButton(panelX + 16, panelY + modeOffset(panelHeight), half,
                Component.translatable("luma.partial_restore.mode_selected_area"),
                () -> selectMode(false), form.outside()
                        ? LumiLegacyButton.Kind.NORMAL : LumiLegacyButton.Kind.SELECTED);
        addLegacyButton(panelX + 24 + half, panelY + modeOffset(panelHeight), half,
                Component.translatable("luma.partial_restore.mode_outside_selection"),
                () -> selectMode(true), form.outside()
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        preview = addLegacyButton(panelX + 16, panelY + previewOffset(panelHeight), half,
                Component.translatable("luma.action.preview_partial_restore"),
                this::preview, LumiLegacyButton.Kind.NORMAL);
        apply = addLegacyButton(panelX + 24 + half,
                panelY + previewOffset(panelHeight), half,
                Component.translatable("luma.action.apply_partial_restore"),
                this::apply, LumiLegacyButton.Kind.PRIMARY);
        addLegacyButton(width / 2 - 60,
                panelY + cancelOffset(panelHeight), 120,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
        updateButtons();
    }

    private void selectMode(boolean outside) {
        form.setOutside(outside);
        localError = "";
        rebuildWidgets();
    }

    private void preview() {
        try {
            BlockAreaTarget area = form.area().orElseThrow();
            UUID requestId = previewRestore.apply(target, area);
            form.beginPreview(requestId, area);
            localError = "";
            updateButtons();
        } catch (RuntimeException failed) {
            localError = message(failed, "Lumi partial Restore preview could not start");
            updateButtons();
        }
    }

    private void apply() {
        try {
            applyRestore.apply(form.previewToken().orElseThrow());
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            localError = message(failed, "Lumi partial Restore could not start");
            updateButtons();
        }
    }

    public void accept(PartialRestorePlanPayload result) {
        if (form.accept(result)) {
            localError = "";
            updateButtons();
        }
    }

    private void updateButtons() {
        if (preview == null) return;
        preview.active = !form.previewPending();
        apply.active = form.canApply() && !form.previewPending();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            renderLegacyWindow(
                    graphics, panelX, panelY, panelWidth, panelHeight);
            graphics.drawCenteredString(font,
                    Component.translatable("luma.partial_restore.title"),
                    width / 2, panelY + 16, LegacyLumiTheme.TEXT);
            graphics.drawCenteredString(font,
                    Component.translatable("luma.partial_restore.lumi_region"),
                    width / 2, panelY + 34, LegacyLumiTheme.ACCENT);
            renderStatus(graphics, panelWidth);
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void renderStatus(GuiGraphics graphics, int panelWidth) {
        String error = localError.isEmpty() ? form.error() : localError;
        Component status = null;
        int color = LegacyLumiTheme.MUTED;
        if (!error.isEmpty()) {
            status = errorText(error);
            color = LegacyLumiTheme.DANGER;
        } else if (form.previewPending()) {
            status = Component.translatable("luma.status.preview_requested");
        } else if (form.previewToken().isPresent()) {
            status = Component.translatable(
                    "luma.partial_restore.summary",
                    form.changedBlocks(), form.changedSections());
            color = form.changedBlocks() == 0
                    ? LegacyLumiTheme.MUTED : LegacyLumiTheme.ACCENT;
        }
        if (status != null) {
            graphics.drawCenteredString(font,
                    font.plainSubstrByWidth(
                            status.getString(), Math.max(1, panelWidth - 40)),
                    width / 2, panelY + statusOffset(panelHeight), color);
        }
    }

    static LegacyModalLayout fitPanel(int screenWidth, int screenHeight) {
        int width = Math.min(PANEL_WIDTH, Math.max(1, screenWidth - 32));
        int height = Math.min(PANEL_HEIGHT, Math.max(1, screenHeight - 16));
        return new LegacyModalLayout(
                Math.max(0, (screenWidth - width) / 2),
                Math.max(0, (screenHeight - height) / 2), width, height);
    }

    static int cancelOffset(int panelHeight) {
        return panelHeight - 28;
    }

    static int previewOffset(int panelHeight) {
        return Math.min(98, cancelOffset(panelHeight) - 56);
    }

    static int modeOffset(int panelHeight) {
        return Math.min(62, previewOffset(panelHeight) - 28);
    }

    static int statusOffset(int panelHeight) {
        return Math.min(128, cancelOffset(panelHeight) - 26);
    }

    private static String message(RuntimeException failed, String fallback) {
        return failed.getMessage() == null ? fallback : failed.getMessage();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
