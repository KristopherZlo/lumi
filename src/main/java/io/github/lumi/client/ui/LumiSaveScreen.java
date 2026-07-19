package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.client.state.ClientHistoryStore;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Focused legacy-style Save form retained for the Alt+S workflow. */
public final class LumiSaveScreen extends LumiLegacyModalScreen {
    private static final int DIALOG_HEIGHT = 226;

    private final Screen parent;
    private final ClientHistoryStore history;
    private final SaveScreenController controller;
    private final Runnable refresh;
    private final SaveScreenController.Intent preferredIntent;
    private final String initialMessage;
    private final Consumer<UUID> previewCapture;
    private final Runnable accepted;
    private LegacyModalLayout layout;
    private EditBox message;
    private EditBox tags;
    private LumiLegacyButton save;
    private LumiLegacyButton amend;
    private String error = "";

    public LumiSaveScreen(
            Screen parent,
            ClientHistoryStore history,
            SaveScreenController controller,
            Runnable refresh) {
        this(parent, history, controller, refresh,
                SaveScreenController.Intent.SAVE, "", ignored -> { }, () -> { });
    }

    public LumiSaveScreen(
            Screen parent,
            ClientHistoryStore history,
            SaveScreenController controller,
            Runnable refresh,
            SaveScreenController.Intent preferredIntent,
            String initialMessage) {
        this(parent, history, controller, refresh,
                preferredIntent, initialMessage, ignored -> { }, () -> { });
    }

    public LumiSaveScreen(
            Screen parent,
            ClientHistoryStore history,
            SaveScreenController controller,
            Runnable refresh,
            SaveScreenController.Intent preferredIntent,
            String initialMessage,
            Consumer<UUID> previewCapture) {
        this(parent, history, controller, refresh, preferredIntent,
                initialMessage, previewCapture, () -> { });
    }

    public LumiSaveScreen(
            Screen parent,
            ClientHistoryStore history,
            SaveScreenController controller,
            Runnable refresh,
            SaveScreenController.Intent preferredIntent,
            String initialMessage,
            Consumer<UUID> previewCapture,
            Runnable accepted) {
        super(parent, Component.translatable("luma.screen.save.title"));
        this.parent = parent;
        this.history = Objects.requireNonNull(history, "history");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.preferredIntent = Objects.requireNonNull(preferredIntent, "preferredIntent");
        this.initialMessage = Objects.requireNonNull(initialMessage, "initialMessage");
        this.previewCapture = Objects.requireNonNull(previewCapture, "previewCapture");
        this.accepted = Objects.requireNonNull(accepted, "accepted");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        layout = LegacyModalLayout.fit(width, height, DIALOG_HEIGHT);
        int x = layout.x();
        int y = layout.y();
        int actionY = y + layout.height() - 28;
        int fieldY = y + 65;

        addLegacyIconButton(x + layout.width() - 32, y + 6, "close",
                Component.translatable("luma.action.close"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
        addLegacyIconButton(x + layout.width() - 32, y + 34, "see-changes",
                Component.translatable("luma.action.refresh_preview"),
                this::refreshPreview, LumiLegacyButton.Kind.NORMAL);

        message = new EditBox(
                font, x + 14, fieldY + 38, layout.width() - 28, 16,
                Component.translatable("luma.save.name_input"));
        message.setMaxLength(SaveScreenController.MAX_NAME_LENGTH);
        message.setHint(Component.translatable("luma.save.name_input"));
        message.setBordered(false);
        message.setTextColor(LegacyLumiTheme.TEXT);
        message.setResponder(value -> setSubmitActive(!value.trim().isEmpty()));
        addRenderableWidget(message);
        message.setValue(initialMessage);

        tags = new EditBox(
                font, x + 14, fieldY + 91, layout.width() - 28, 16,
                Component.translatable("luma.history.tags_input"));
        tags.setMaxLength(io.github.lumi.domain.model.VersionTags.MAX_SERIALIZED_LENGTH);
        tags.setHint(Component.translatable("luma.history.tags_input"));
        tags.setBordered(false);
        tags.setTextColor(LegacyLumiTheme.TEXT);
        addRenderableWidget(tags);

        int contentWidth = layout.width() - 12;
        int buttonWidth = (contentWidth - 8) / 3;
        save = addLegacyButton(x + 6, actionY, buttonWidth,
                Component.translatable("luma.action.save_build"),
                () -> submit(SaveScreenController.Intent.SAVE),
                preferredIntent == SaveScreenController.Intent.SAVE
                        ? LumiLegacyButton.Kind.PRIMARY : LumiLegacyButton.Kind.NORMAL);
        amend = addLegacyButton(x + 10 + buttonWidth, actionY, buttonWidth,
                Component.translatable("luma.action.amend_version"),
                () -> submit(SaveScreenController.Intent.AMEND),
                preferredIntent == SaveScreenController.Intent.AMEND
                        ? LumiLegacyButton.Kind.PRIMARY : LumiLegacyButton.Kind.NORMAL);
        addLegacyButton(x + 14 + buttonWidth * 2, actionY, buttonWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
        setSubmitActive(!initialMessage.trim().isEmpty());
        refreshPreview();
    }

    private void setSubmitActive(boolean active) {
        if (save != null) {
            save.active = active;
        }
        if (amend != null) {
            amend.active = active;
        }
    }

    @Override
    protected void setInitialFocus() {
        if (message != null) {
            setInitialFocus(message);
            message.setFocused(true);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == InputConstants.KEY_RETURN
                || event.key() == InputConstants.KEY_NUMPADENTER) && save.active) {
            submit(preferredIntent);
            return true;
        }
        return super.keyPressed(event);
    }

    private void submit(SaveScreenController.Intent intent) {
        SaveScreenController.Submission submission =
                controller.submit(message.getValue(), tags.getValue(), intent);
        error = submission.error();
        if (submission.accepted()) {
            submission.requestId().ifPresent(previewCapture);
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("luma.status.save_started"), true);
            }
            accepted.run();
            minecraft.setScreen(parent);
        }
    }

    private void refreshPreview() {
        try {
            refresh.run();
            error = "";
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi preview could not refresh" : failed.getMessage();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        renderLegacyWindow(
                graphics, layout.x(), layout.y(), layout.width(), layout.height());
        drawDialog(graphics);
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void drawDialog(GuiGraphics graphics) {
        int x = layout.x();
        int y = layout.y();
        int actionY = y + layout.height() - 28;
        int fieldY = y + 65;
        int fieldHeight = Math.min(67, actionY - fieldY - 8);
        graphics.drawString(font, title, x + 10, y + 12,
                LegacyLumiTheme.TEXT, false);

        LegacyLumiTheme.outlined(graphics, x + 6, y + 34,
                layout.width() - 12, 26,
                LegacyLumiTheme.STATUS, LegacyLumiTheme.STATUS_BORDER);
        Component status = status();
        int statusColor = error.isEmpty()
                ? LegacyLumiTheme.ACCENT : LegacyLumiTheme.DANGER;
        graphics.drawString(font,
                font.plainSubstrByWidth(status.getString(), layout.width() - 132),
                x + 12, y + 43, statusColor, false);

        LegacyLumiTheme.outlined(graphics, x + 6, fieldY,
                layout.width() - 12, fieldHeight,
                LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
        graphics.drawString(font, Component.translatable("luma.save.name_input"),
                x + 12, fieldY + 8, LegacyLumiTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("luma.quick_save.name_help"),
                x + 12, fieldY + 20, LegacyLumiTheme.MUTED, false);
        LegacyLumiTheme.outlined(graphics, x + 11, fieldY + 34,
                layout.width() - 22, 20,
                LegacyLumiTheme.WINDOW, LegacyLumiTheme.PANEL_BORDER);

        LegacyLumiTheme.outlined(graphics, x + 6, fieldY + 72,
                layout.width() - 12, 43,
                LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
        graphics.drawString(font, Component.translatable("luma.save.tags_title"),
                x + 12, fieldY + 78, LegacyLumiTheme.TEXT, false);
        LegacyLumiTheme.outlined(graphics, x + 11, fieldY + 87,
                layout.width() - 22, 20,
                LegacyLumiTheme.WINDOW, LegacyLumiTheme.PANEL_BORDER);
    }

    private Component status() {
        if (!error.isEmpty()) {
            return error.startsWith("luma.")
                    ? Component.translatable(error) : Component.literal(error);
        }
        int pending = history.state().snapshot()
                .map(snapshot -> snapshot.pendingKeys()).orElse(0);
        return pending == 0
                ? Component.translatable("luma.dashboard.pending_clean")
                : Component.translatable("luma.dashboard.workspace_pending", pending);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
