package io.github.lumi.client.ui;

import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.PartialRestorePlanPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Restore confirmation with selection-aware whole, selected, and outside modes. */
public final class LumiRestoreScreen extends LumiModalScreen {
    private static final int PANEL_WIDTH = 440;
    private static final int FULL_PANEL_HEIGHT = 120;
    private static final int PARTIAL_PANEL_HEIGHT = 184;
    private final Screen parent;
    private final CommitId target;
    private final Function<CommitId, UUID> fullRestore;
    private final BiFunction<CommitId, BlockAreaTarget, UUID> previewRestore;
    private final Function<UUID, UUID> applyRestore;
    private final Consumer<UUID> accepted;
    private final PartialRestoreFormState form;
    private final boolean selectionAvailable;
    private RestoreMode mode = RestoreMode.WHOLE;
    private LumiButton preview;
    private LumiButton apply;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private String localError = "";

    public LumiRestoreScreen(
            Screen parent,
            CommitId target,
            String message,
            Function<CommitId, UUID> fullRestore,
            Optional<BlockBox> selection,
            BiFunction<CommitId, BlockAreaTarget, UUID> previewRestore,
            Function<UUID, UUID> applyRestore,
            Consumer<UUID> accepted) {
        super(Component.translatable("luma.restore.confirm_title", message));
        this.parent = parent;
        this.target = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        this.fullRestore = Objects.requireNonNull(fullRestore, "fullRestore");
        this.previewRestore = Objects.requireNonNull(previewRestore, "previewRestore");
        this.applyRestore = Objects.requireNonNull(applyRestore, "applyRestore");
        this.accepted = Objects.requireNonNull(accepted, "accepted");
        Optional<BlockBox> bounds = Objects.requireNonNull(selection, "selection");
        selectionAvailable = bounds.isPresent();
        form = new PartialRestoreFormState(target, bounds);
    }

    @Override
    protected void init() {
        beginScreenInit();
        panelHeight = Math.min(selectionAvailable
                ? PARTIAL_PANEL_HEIGHT : FULL_PANEL_HEIGHT, height - 16);
        panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = Math.max(0, (width - panelWidth) / 2);
        panelY = Math.max(8, (height - panelHeight) / 2);
        if (selectionAvailable) {
            addModeButtons();
            addPartialActions();
        } else {
            addFullActions();
        }
        updateButtons();
    }

    private void addModeButtons() {
        int innerWidth = panelWidth - 32;
        int choiceWidth = (innerWidth - 16) / 3;
        addModeButton(panelX + 16, choiceWidth, RestoreMode.WHOLE,
                "luma.action.restore_whole_save");
        addModeButton(panelX + 24 + choiceWidth, choiceWidth,
                RestoreMode.SELECTED,
                "luma.partial_restore.mode_selected_area");
        addModeButton(panelX + 32 + choiceWidth * 2,
                innerWidth - choiceWidth * 2 - 16, RestoreMode.OUTSIDE,
                "luma.partial_restore.mode_outside_selection");
    }

    private void addModeButton(
            int x, int buttonWidth, RestoreMode value, String translationKey) {
        addButton(x, panelY + modeOffset(panelHeight), buttonWidth,
                Component.translatable(translationKey), () -> selectMode(value),
                mode == value ? LumiButton.Kind.SELECTED : LumiButton.Kind.NORMAL);
    }

    private void addPartialActions() {
        int half = (panelWidth - 40) / 2;
        preview = addButton(panelX + 16, panelY + actionOffset(panelHeight), half,
                Component.translatable("luma.action.preview_partial_restore"),
                this::preview, LumiButton.Kind.NORMAL);
        apply = addButton(panelX + 24 + half, panelY + actionOffset(panelHeight), half,
                mode == RestoreMode.WHOLE
                        ? Component.translatable("luma.action.restore")
                        : Component.translatable("luma.action.apply_partial_restore"),
                this::submit, LumiButton.Kind.PRIMARY);
        addButton(width / 2 - 60, panelY + cancelOffset(panelHeight), 120,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiButton.Kind.NORMAL);
    }

    private void addFullActions() {
        int buttonWidth = Math.max(80, (panelWidth - 48) / 2);
        addButton(panelX + 16, panelY + panelHeight - 34, buttonWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiButton.Kind.NORMAL);
        apply = addButton(panelX + panelWidth - 16 - buttonWidth,
                panelY + panelHeight - 34, buttonWidth,
                Component.translatable("luma.action.restore"),
                this::submit, LumiButton.Kind.PRIMARY);
    }

    private void selectMode(RestoreMode next) {
        mode = Objects.requireNonNull(next, "next");
        if (mode != RestoreMode.WHOLE) {
            form.setOutside(mode == RestoreMode.OUTSIDE);
        }
        localError = "";
        rebuildWidgets();
    }

    private void preview() {
        try {
            BlockAreaTarget area = form.area().orElseThrow();
            form.beginPreview(previewRestore.apply(target, area), area);
            localError = "";
        } catch (RuntimeException failed) {
            localError = message(failed, "Lumi partial Restore preview could not start");
        }
        updateButtons();
    }

    private void submit() {
        try {
            UUID requestId = mode == RestoreMode.WHOLE
                    ? fullRestore.apply(target)
                    : applyRestore.apply(form.previewToken().orElseThrow());
            accepted.accept(requestId);
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            localError = message(failed, "Lumi Restore could not start");
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
        if (apply == null) return;
        if (!selectionAvailable || mode == RestoreMode.WHOLE) {
            apply.active = true;
            if (preview != null) preview.active = false;
            return;
        }
        preview.active = !form.previewPending();
        apply.active = form.canApply() && !form.previewPending();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
            renderWindow(graphics, panelX, panelY, panelWidth, panelHeight);
            graphics.drawCenteredString(font, clippedCenteredHeader(
                    title, width / 2, panelX + 16, panelX + panelWidth - 16),
                    width / 2, panelY + 16, LumiTheme.TEXT);
            if (selectionAvailable) {
                graphics.drawCenteredString(font,
                        Component.translatable("luma.restore.selection_choice_help"),
                        width / 2, panelY + 34, LumiTheme.MUTED);
            }
            renderStatus(graphics);
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
    }

    private void renderStatus(GuiGraphics graphics) {
        String error = localError.isEmpty() ? form.error() : localError;
        Component status = null;
        int color = LumiTheme.MUTED;
        if (!error.isEmpty()) {
            status = errorText(error);
            color = LumiTheme.DANGER;
        } else if (form.previewPending()) {
            status = Component.translatable("luma.status.preview_requested");
        } else if (form.previewToken().isPresent()) {
            status = Component.translatable("luma.partial_restore.summary",
                    form.changedBlocks(), form.changedSections());
            color = form.changedBlocks() == 0 ? LumiTheme.MUTED : LumiTheme.ACCENT;
        }
        if (status != null) {
            graphics.drawCenteredString(font, font.plainSubstrByWidth(
                    status.getString(), Math.max(1, panelWidth - 40)),
                    width / 2, panelY + statusOffset(panelHeight), color);
        }
    }

    static int cancelOffset(int height) { return height - 28; }
    static int actionOffset(int height) { return Math.min(92, cancelOffset(height) - 54); }
    static int modeOffset(int height) { return Math.min(58, actionOffset(height) - 28); }
    static int statusOffset(int height) { return Math.min(122, cancelOffset(height) - 24); }

    private static String message(RuntimeException failed, String fallback) {
        return failed.getMessage() == null ? fallback : failed.getMessage();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }

    private enum RestoreMode { WHOLE, SELECTED, OUTSIDE }
}
