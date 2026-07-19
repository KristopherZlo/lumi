package io.github.lumi.client.ui;

import io.github.lumi.client.onboarding.ClientContextualHelpHint;
import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.PartialRestorePlanPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Full and preview-gated partial Restore controls retained from legacy Lumi. */
public final class LumiRestoreScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 540;
    private static final int BASE_PANEL_HEIGHT = 300;
    private static final int HINT_OFFSET = 54;
    private final Screen parent;
    private final CommitId target;
    private final Supplier<Optional<BlockBox>> selection;
    private final BiConsumer<CommitId, Boolean> fullRestore;
    private final BiFunction<CommitId, BlockAreaTarget, UUID> previewPartialRestore;
    private final Function<UUID, UUID> applyPartialRestore;
    private final PartialRestoreFormState form;
    private String localError = "";
    private LumiLegacyButton selectedMode;
    private LumiLegacyButton outsideMode;
    private LumiLegacyButton useSelection;
    private LumiLegacyButton preview;
    private LumiLegacyButton apply;
    private int panelX;
    private int panelY;
    private int panelHeight;
    private int contentOffset;

    public LumiRestoreScreen(
            Screen parent,
            CommitId target,
            String message,
            Supplier<Optional<BlockBox>> selection,
            BiConsumer<CommitId, Boolean> fullRestore,
            BiFunction<CommitId, BlockAreaTarget, UUID> previewPartialRestore,
            Function<UUID, UUID> applyPartialRestore) {
        super(Component.translatable("luma.restore.confirm_title", message));
        this.parent = parent;
        this.target = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        this.selection = Objects.requireNonNull(selection, "selection");
        this.fullRestore = Objects.requireNonNull(fullRestore, "fullRestore");
        this.previewPartialRestore = Objects.requireNonNull(
                previewPartialRestore, "previewPartialRestore");
        this.applyPartialRestore = Objects.requireNonNull(
                applyPartialRestore, "applyPartialRestore");
        form = new PartialRestoreFormState(target, selection.get());
    }

    @Override
    protected void init() {
        beginLegacyInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelHeight = BASE_PANEL_HEIGHT + HINT_OFFSET;
        panelY = Math.max(3, (height - panelHeight) / 2);
        boolean hintVisible = addContextualHint(
                ClientContextualHelpHint.PARTIAL_RESTORE,
                panelX + 16, panelY + 96, panelWidth - 32);
        contentOffset = hintVisible ? HINT_OFFSET : 0;
        if (!hintVisible) {
            panelHeight = BASE_PANEL_HEIGHT;
            panelY = Math.max(8, (height - panelHeight) / 2);
        }

        int contentX = panelX + 16;
        int innerWidth = panelWidth - 32;
        int half = (innerWidth - 8) / 2;
        addLegacyButton(contentX, panelY + 68, half,
                Component.translatable("luma.action.restore_whole_save"),
                () -> submitFull(true), LumiLegacyButton.Kind.PRIMARY);
        addLegacyButton(contentX + half + 8, panelY + 68, half,
                Component.translatable("luma.action.restore_without_entities"),
                () -> submitFull(false), LumiLegacyButton.Kind.NORMAL);

        int modeY = panelY + 112 + contentOffset;
        int modeWidth = Math.max(40, (innerWidth - 88) / 2);
        selectedMode = addLegacyButton(contentX + 80, modeY, modeWidth,
                Component.translatable("luma.partial_restore.mode_selected_area"),
                () -> setOutside(false), form.outside()
                        ? LumiLegacyButton.Kind.NORMAL : LumiLegacyButton.Kind.SELECTED);
        outsideMode = addLegacyButton(contentX + 84 + modeWidth, modeY, modeWidth,
                Component.translatable("luma.partial_restore.mode_outside_selection"),
                () -> setOutside(true), form.outside()
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);

        addBoundsRow(contentX, innerWidth, panelY + 140 + contentOffset,
                "luma.partial_restore.min",
                form.minX(), form.minY(), form.minZ(),
                form::setMinX, form::setMinY, form::setMinZ);
        addBoundsRow(contentX, innerWidth, panelY + 166 + contentOffset,
                "luma.partial_restore.max",
                form.maxX(), form.maxY(), form.maxZ(),
                form::setMaxX, form::setMaxY, form::setMaxZ);

        useSelection = addLegacyButton(contentX, panelY + 192 + contentOffset, half,
                Component.translatable("luma.action.use_selected_area"),
                this::useSelection, LumiLegacyButton.Kind.NORMAL);
        preview = addLegacyButton(contentX, panelY + 218 + contentOffset, half,
                Component.translatable("luma.action.preview_partial_restore"),
                this::previewPartialRestore, LumiLegacyButton.Kind.NORMAL);
        apply = addLegacyButton(contentX + half + 8,
                panelY + 218 + contentOffset, half,
                Component.translatable("luma.action.apply_partial_restore"),
                this::applyPartialRestore, LumiLegacyButton.Kind.PRIMARY);
        addLegacyButton(width / 2 - 60, panelY + panelHeight - 28, 120,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
        updateButtons();
    }

    private void addBoundsRow(
            int x, int width, int y, String label,
            String valueX, String valueY, String valueZ,
            Consumer<String> onX, Consumer<String> onY, Consumer<String> onZ) {
        int gap = 6;
        int fieldX = x + 80;
        int fieldWidth = Math.max(24, (width - 80 - gap * 2) / 3);
        addEditBox(fieldX, y, fieldWidth, valueX, label, onX);
        addEditBox(fieldX + fieldWidth + gap, y, fieldWidth, valueY, label, onY);
        addEditBox(fieldX + (fieldWidth + gap) * 2,
                y, fieldWidth, valueZ, label, onZ);
    }

    private void addEditBox(
            int x, int y, int width, String value, String label,
            Consumer<String> responder) {
        EditBox box = new EditBox(font, x, y, width, 20,
                Component.translatable(label));
        box.setMaxLength(11);
        box.setValue(value);
        box.setResponder(valueChanged -> {
            localError = "";
            responder.accept(valueChanged);
        });
        addRenderableWidget(box);
    }

    private void setOutside(boolean outside) {
        form.setOutside(outside);
        localError = "";
        rebuildWidgets();
    }

    private void useSelection() {
        selection.get().ifPresent(bounds -> {
            form.useSelection(bounds);
            localError = "";
            rebuildWidgets();
        });
    }

    private void previewPartialRestore() {
        try {
            BlockAreaTarget area = form.area().orElseThrow(() ->
                    new IllegalArgumentException(
                            "luma.status.partial_restore_invalid_bounds"));
            UUID requestId = previewPartialRestore.apply(target, area);
            form.beginPreview(requestId, area);
            localError = "";
            updateButtons();
        } catch (RuntimeException failed) {
            showError(failed, "Lumi partial Restore preview could not start");
        }
    }

    private void applyPartialRestore() {
        try {
            applyPartialRestore.apply(form.previewToken().orElseThrow(() ->
                    new IllegalStateException("Preview partial Restore first")));
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            showError(failed, "Lumi partial Restore could not start");
        }
    }

    public void accept(PartialRestorePlanPayload result) {
        if (form.accept(result)) {
            localError = "";
            updateButtons();
        }
    }

    private void submitFull(boolean includeEntities) {
        try {
            fullRestore.accept(target, includeEntities);
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            showError(failed, "Lumi Restore could not start");
        }
    }

    private void showError(RuntimeException failed, String fallback) {
        localError = failed.getMessage() == null ? fallback : failed.getMessage();
        updateButtons();
    }

    @Override
    public void tick() {
        super.tick();
        updateButtons();
    }

    private void updateButtons() {
        if (useSelection == null) {
            return;
        }
        useSelection.active = selection.get().isPresent();
        preview.active = !form.previewPending();
        apply.active = form.canApply() && !form.previewPending();
        selectedMode.active = form.outside();
        outsideMode.active = !form.outside();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            int panelWidth = Math.min(PANEL_WIDTH, width - 32);
            renderLegacyWindow(graphics, panelX, panelY, panelWidth, panelHeight);
            graphics.drawCenteredString(font, title, width / 2, panelY + 14,
                    LegacyLumiTheme.TEXT);
            graphics.drawCenteredString(font,
                    Component.translatable("luma.restore.confirm_help"),
                    width / 2, panelY + 32, LegacyLumiTheme.MUTED);
            graphics.drawCenteredString(font,
                    Component.translatable("luma.restore.confirm_safety"),
                    width / 2, panelY + 44, LegacyLumiTheme.MUTED);
            graphics.drawCenteredString(font,
                    Component.translatable("luma.restore.entities_help"),
                    width / 2, panelY + 56, LegacyLumiTheme.MUTED);
            graphics.drawString(font,
                    Component.translatable("luma.partial_restore.title"),
                    panelX + 16, panelY + 98 + contentOffset,
                    LegacyLumiTheme.TEXT, false);
            graphics.drawString(font,
                    Component.translatable("luma.partial_restore.mode"),
                    panelX + 16, panelY + 118 + contentOffset,
                    LegacyLumiTheme.MUTED, false);
            graphics.drawString(font,
                    Component.translatable("luma.partial_restore.min"),
                    panelX + 16, panelY + 146 + contentOffset,
                    LegacyLumiTheme.MUTED, false);
            graphics.drawString(font,
                    Component.translatable("luma.partial_restore.max"),
                    panelX + 16, panelY + 172 + contentOffset,
                    LegacyLumiTheme.MUTED, false);
            if (form.selectionSource()) {
                graphics.drawString(font,
                        Component.translatable("luma.partial_restore.lumi_region"),
                        panelX + panelWidth / 2 + 8,
                        panelY + 198 + contentOffset,
                        LegacyLumiTheme.ACCENT, false);
            }
            renderStatus(graphics, panelWidth);
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void renderStatus(GuiGraphics graphics, int panelWidth) {
        Component status = null;
        int color = LegacyLumiTheme.MUTED;
        String error = localError.isEmpty() ? form.error() : localError;
        if (!error.isEmpty()) {
            status = errorText(error);
            color = LegacyLumiTheme.DANGER;
        } else if (form.previewPending()) {
            status = Component.translatable("luma.status.preview_requested");
        } else if (form.previewToken().isPresent() && form.changedBlocks() == 0) {
            status = Component.translatable(form.outside()
                    ? "luma.status.partial_restore_no_changes_outside_selection"
                    : "luma.status.partial_restore_no_changes_selected");
        } else if (form.previewToken().isPresent()) {
            status = Component.translatable(
                    "luma.partial_restore.summary",
                    form.changedBlocks(), form.changedSections());
            color = LegacyLumiTheme.ACCENT;
        }
        if (status != null) {
            String text = font.plainSubstrByWidth(
                    status.getString(), Math.max(1, panelWidth - 40));
            graphics.drawCenteredString(font, text, width / 2,
                    panelY + 246 + contentOffset, color);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
